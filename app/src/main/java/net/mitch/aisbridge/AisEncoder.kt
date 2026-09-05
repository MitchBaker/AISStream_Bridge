package net.mitch.aisbridge

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

object AisEncoder {

    // Payload packing: 6-bit value -> transmitted ASCII char.
    private const val PACK =
        "0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVW`abcdefghijklmnopqrstuvw"

    // ITU character-value table for TEXT fields (A=1, digits=16-25, '@'=0).
    private const val ITU6 =
        "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#\$%&'()*+,-./0123456789:;<=>?"

    // Resend a vessel's name at most this often. 3 minutes — double the real
    // AIS static-data cadence consideration, cheap on localhost UDP, and
    // lets OsmAnd recover names quickly after restarts/target expiry.
    private const val NAME_RESEND_MS = 3L * 60 * 1000

    // Same idea for synthesized static frames: after the initial send we
    // only refresh every 3 minutes so a chatty vessel's position reports
    // don't multiply into a stream of two-sentence static broadcasts.
    private const val SYNTH_RESEND_MS = 3L * 60 * 1000

    private val seqCounter = AtomicInteger()
    // Threading note: read/written from the OkHttp websocket thread AND
    // cleared from the main thread (ACTION_CLEAR_STORE, ACTION_IMPORT,
    // session start). ConcurrentHashMap removes the corruption hazard; the
    // residual race (an entry surviving a clear by a millisecond) is benign
    // — the next resend window self-heals.
    private val nameCache = ConcurrentHashMap<Long, Pair<Long, String>>()   // mmsi -> (elapsedMs, name)
    private val synthCache = ConcurrentHashMap<Long, Long>()                // mmsi -> last send (elapsedMs)

    private fun ubits(v: Long?, n: Int, default: Long = 0): String {
        val x = (v ?: default).coerceIn(0L, (1L shl n) - 1)
        return x.toString(2).padStart(n, '0')
    }

    private fun sbits(v: Long?, n: Int, default: Long = -(1L shl (n - 1))): String {
        val lo = -(1L shl (n - 1)); val hi = (1L shl (n - 1)) - 1
        var x = (v ?: default).coerceIn(lo, hi)
        if (x < 0) x += (1L shl n)
        return x.toString(2).padStart(n, '0')
    }

    private fun text6(s: String?, width: Int): String {
        val sb = StringBuilder()
        for (c in (s ?: "").uppercase()) {
            var v = ITU6.indexOf(c)
            if (v < 0) v = 32                       // space for unmappable chars
            sb.append(v.toString(2).padStart(6, '0'))
        }
        val padded = sb.toString() + "0".repeat(6 * width)
        return padded.take(6 * width)
    }

    // Packs a raw bitstring into 6-bit-per-char AIS armor, zero-padding the
    // final character as needed. Returns the packed payload AND the number
    // of pad bits actually added, so callers can report it correctly in the
    // fill-bits field of the AIVDM sentence (required by spec — a receiver
    // needs this to know how many trailing bits of the last char to ignore).
    private fun payload6(bitsIn: String): Pair<String, Int> {
        val pad = (6 - (bitsIn.length % 6)) % 6
        val b = bitsIn + "0".repeat(pad)
        val out = StringBuilder()
        var i = 0
        while (i < b.length) {
            out.append(PACK[b.substring(i, i + 6).toInt(2)])
            i += 6
        }
        return out.toString() to pad
    }

    private fun sentence(bits: String, total: Int = 1, frag: Int = 1,
                         seq: Int = seqCounter.incrementAndGet()): String {
        val (payload, fillBits) = payload6(bits)
        val body = "AIVDM,$total,$frag,${seq % 10},A,$payload,$fillBits"
        var chk = 0
        for (ch in body) chk = chk xor ch.code
        return "!$body*" + chk.toString(16).uppercase().padStart(2, '0')
    }

    // VERDICT (empirical, 2026-09-03 log): aisstream's RateOfTurn is the RAW
    // signed 8-bit AIS field, un-transformed — proven by -128 (ITU "no turn
    // indicator" sentinel, ROT #3 sample) and repeated exact 127 saturation
    // codes. Pass it through verbatim so all sentinels survive the round trip:
    //   -128  -> ROT not available (OsmAnd shows blank)
    //   ±127  -> turning right/left faster than the 708 deg/min limit
    //   small ints -> real quantized turn rates, decoded by consumers
    private fun rotBits(rot: Double?): String =
        sbits(rot?.roundToInt()?.toLong() ?: -128L, 8)

    private fun dbl(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key) else null

    // Reads navigational status under either spelling; 15 = AIS N/A.
    private fun statusOf(o: JSONObject): Int =
        if (o.has("NavigationalStatus") && !o.isNull("NavigationalStatus"))
            o.optInt("NavigationalStatus")
        else if (o.has("Status") && !o.isNull("Status"))
            o.optInt("Status")
        else 15

    // ------------------------------------------------------ position reports

    fun encodePosition(inner: JSONObject): String {
        val mmsi = inner.optLong("UserID", 0L)
        var msgType = inner.optInt("MessageID", 1)
        if (msgType != 1 && msgType != 2 && msgType != 3 && msgType != 18) msgType = 1

        val b = StringBuilder()
        b.append(ubits(msgType.toLong(), 6))
        b.append(ubits(0, 2))
        b.append(ubits(mmsi, 30))
        if (msgType == 18) {
            b.append("0".repeat(8))                              // Class B reserved
        } else {
            // Pass through the REAL status, or 15 (not available) — never
            // fabricate one from SOG.
            b.append(ubits(statusOf(inner).toLong(), 4, 15))
            b.append(rotBits(dbl(inner, "RateOfTurn")))
        }

        val sog = dbl(inner, "Sog")
        // 102.3 is the AIS "not available" SOG — forward it verbatim so the
        // dead value survives the round trip (102.3 * 10 = 1023).
        b.append(if (sog == null || sog >= 102.3) ubits(1023, 10) else ubits(Math.round(sog * 10), 10))

        // Real position-accuracy flag from the feed (default high).
        b.append(if (inner.optBoolean("PositionAccuracy", true)) "1" else "0")

        val lon = dbl(inner, "Longitude"); val lat = dbl(inner, "Latitude")
        b.append(if (lon == null) sbits(0x6791AC0L, 28) else sbits(Math.round(lon * 600000), 28))
        b.append(if (lat == null) sbits(0x3412140L, 27) else sbits(Math.round(lat * 600000), 27))

        val cog = dbl(inner, "Cog")
        b.append(if (cog == null) ubits(3600, 12) else ubits(Math.round(cog * 10), 12))

        val hdg: Int? = if (inner.has("TrueHeading") && !inner.isNull("TrueHeading"))
            inner.optInt("TrueHeading") else null
        b.append(ubits(if (hdg == null || hdg !in 0..359) 511L else hdg.toLong(), 9))

        // Real UTC-second timestamp when present (clamped to the valid 0-59
        // range), else 60 = not available. The N/A sentinel must NOT be
        // clamped down to 59 — that would falsely claim a fixed :59 second.
        val ts: Int = if (inner.has("Timestamp") && !inner.isNull("Timestamp"))
            inner.optInt("Timestamp").coerceIn(0, 59)
        else 60
        b.append(ubits(ts.toLong(), 6))
        b.append("0".repeat(if (msgType == 18) 29 else 25))      // pad to 168 bits
        return sentence(b.toString())
    }

    // AIS message type 19: Extended Class B "CS" position report. Carries
    // the standard position block PLUS static cargo (name, type, dimensions)
    // — this is the ONLY static data source for B-CS vessels, which never
    // transmit type 5. Fixed 312 bits = 52 six-bit chars, single sentence:
    //   type(6) + repeat(2) + mmsi(30) + regional(8) + sog(10) + acc(1)
    //   + lon(28) + lat(27) + cog(12) + hdg(9) + ts(6) + reserved(4)
    //   + name(120) + shiptype(8) + dims(30) + fixtype(4) + raim(1)
    //   + dte(1) + assigned(1) + spare(4)
    fun encodePosition19(inner: JSONObject): String {
        val b = StringBuilder()
        b.append(ubits(19, 6)); b.append(ubits(0, 2))
        b.append(ubits(inner.optLong("UserID", 0L), 30))
        b.append("0".repeat(8))                                  // regional reserved
        val sog = dbl(inner, "Sog")
        b.append(if (sog == null || sog >= 102.3) ubits(1023, 10)
        else ubits(Math.round(sog * 10), 10))
        b.append(if (inner.optBoolean("PositionAccuracy", true)) "1" else "0")
        val lon = dbl(inner, "Longitude"); val lat = dbl(inner, "Latitude")
        b.append(if (lon == null) sbits(0x6791AC0L, 28) else sbits(Math.round(lon * 600000), 28))
        b.append(if (lat == null) sbits(0x3412140L, 27) else sbits(Math.round(lat * 600000), 27))
        val cog = dbl(inner, "Cog")
        b.append(if (cog == null) ubits(3600, 12) else ubits(Math.round(cog * 10), 12))
        val hdg: Int? = if (inner.has("TrueHeading") && !inner.isNull("TrueHeading"))
            inner.optInt("TrueHeading") else null
        b.append(ubits(if (hdg == null || hdg !in 0..359) 511L else hdg.toLong(), 9))
        val ts: Int = if (inner.has("Timestamp") && !inner.isNull("Timestamp"))
            inner.optInt("Timestamp").coerceIn(0, 59)
        else 60
        b.append(ubits(ts.toLong(), 6))
        b.append("0".repeat(4))                                  // reserved
        // Static cargo — pass through what the feed carried; missing/
        // blank fields encode as the spec's N/A patterns (name -> '@'
        // padding, shiptype/dims -> 0 = unavailable).
        b.append(text6(inner.optString("Name", ""), 20))
        b.append(ubits(inner.optLong("Type", 0L), 8))
        val d = inner.optJSONObject("Dimension") ?: JSONObject()
        b.append(ubits(d.optLong("A", 0L), 9)); b.append(ubits(d.optLong("B", 0L), 9))
        b.append(ubits(d.optLong("C", 0L), 6)); b.append(ubits(d.optLong("D", 0L), 6))
        b.append(ubits(inner.optInt("FixType", 0).toLong(), 4))
        b.append(if (inner.optBoolean("Raim", false)) "1" else "0")
        b.append(if (inner.optBoolean("Dte", true)) "1" else "0")   // DTE 1 = not available
        b.append(if (inner.optBoolean("AssignedMode", false)) "1" else "0")
        b.append("0000")                                         // spare -> 312 bits
        return sentence(b.toString())
    }

    // AIS message type 27: Long-range broadcast position (coarse: 1-knot SOG,
    // 1/10-minute positions, no heading). Fixed 96 bits:
    //   type(6) + repeat(2) + mmsi(30) + accuracy(1) + raim(1) + status(4)
    //   + lon(18) + lat(17) + sog(6) + cog(9) + gnss_status(1) + spare(1)
    fun encodePosition27(inner: JSONObject): String {
        val b = StringBuilder()
        b.append(ubits(27, 6)); b.append(ubits(0, 2))
        b.append(ubits(inner.optLong("UserID", 0L), 30))
        b.append("1")                                            // position accuracy high
        b.append("0")                                            // raim
        b.append(ubits(statusOf(inner).toLong(), 4, 15))
        val lon = dbl(inner, "Longitude"); val lat = dbl(inner, "Latitude")
        b.append(if (lon == null) sbits(108600, 18) else sbits(Math.round(lon * 600), 18))
        b.append(if (lat == null) sbits(54600, 17) else sbits(Math.round(lat * 600), 17))
        val sog = dbl(inner, "Sog")
        b.append(if (sog == null || sog >= 63) ubits(63, 6) else ubits(Math.round(sog), 6))
        val cog = dbl(inner, "Cog")
        b.append(if (cog == null) ubits(360, 9) else ubits(Math.round(cog), 9))
        b.append("0")                                            // GNSS position status (current GNSS fix)
        b.append("0")                                            // spare -> 96 bits total
        return sentence(b.toString())
    }

    // ------------------------------------------------------ static reports

    fun encodeStaticA(s: JSONObject): List<String> {
        val d = s.optJSONObject("Dimension") ?: JSONObject()
        val eta = s.optJSONObject("Eta") ?: JSONObject()
        val b = StringBuilder()
        b.append(ubits(5, 6)); b.append(ubits(0, 2)); b.append(ubits(s.optLong("UserID", 0L), 30))
        b.append(ubits(0, 2))                                    // AIS version
        b.append(ubits(if (s.isNull("ImoNumber")) 0L else s.optLong("ImoNumber"), 30))
        b.append(text6(s.optString("CallSign", ""), 7))
        b.append(text6(s.optString("Name", ""), 20))
        b.append(ubits(s.optLong("Type", 0L), 8))
        b.append(ubits(d.optLong("A", 0L), 9)); b.append(ubits(d.optLong("B", 0L), 9))
        b.append(ubits(d.optLong("C", 0L), 6)); b.append(ubits(d.optLong("D", 0L), 6))
        b.append(ubits(1, 4))                                    // EPFD: GPS
        b.append(ubits(eta.optInt("Month", 0).toLong(), 4))
        b.append(ubits(eta.optInt("Day", 0).toLong(), 5))
        b.append(ubits(eta.optInt("Hour", 24).toLong(), 5))
        b.append(ubits(eta.optInt("Minute", 60).toLong(), 6))
        val dr = dbl(s, "MaximumStaticDraught")
        b.append(if (dr == null) ubits(255, 8) else ubits(Math.round(dr * 10), 8))
        b.append(text6(s.optString("Destination", ""), 20))
        b.append("00")                                           // DTE + spare

        val bits = b.toString()
        val seq = seqCounter.incrementAndGet()
        return listOf(
            sentence(bits.take(168), 2, 1, seq),
            sentence(bits.substring(168), 2, 2, seq)
        )
    }

    // AIS message type 24, Part A (name). Spec frame is 168 bits:
    // type6 + repeat2 + mmsi30 + partno2 + name120 + spare8.
    // NOTE: does NOT cleanName — callers pass store/relay names and must
    // handle UNKNOWN suppression themselves. A relayed blank name encodes
    // as all-'@' padding, which is the spec's N/A form.
    fun encodeStaticBName(userId: Long, name: String): String {
        val b = StringBuilder()
        b.append(ubits(24, 6)); b.append(ubits(0, 2)); b.append(ubits(userId, 30))
        b.append(ubits(0, 2))                                    // part A
        b.append(text6(name, 20))
        b.append("00000000")                                     // spare -> 168 bits
        return sentence(b.toString())
    }

    // AIS message type 24, Part B (ship type / vendor / callsign / dims).
    // 168 bits: type6 + repeat2 + mmsi30 + partno2 + shiptype8 + vendor42
    // + callsign42 + dims30 + fixtype4 + spare2. Used for both SYNTHESIS
    // for Class B vessels and RELAY of real StaticDataReport Part Bs.
    // Missing shiptype/fixtype encode as 0 = unavailable per spec.
    fun encodeStaticBPartB(userId: Long, shipType: Long?, vendorId: String?,
                           callSign: String?, dim: JSONObject?, fixType: Long?): String {
        val b = StringBuilder()
        b.append(ubits(24, 6)); b.append(ubits(0, 2)); b.append(ubits(userId, 30))
        b.append(ubits(1, 2))                                    // part number B
        b.append(ubits(shipType, 8))
        b.append(text6(vendorId, 7))                            // vendor ID (or '@' padding)
        b.append(text6(callSign, 7))
        val d = dim ?: JSONObject()
        b.append(ubits(d.optLong("A", 0L), 9)); b.append(ubits(d.optLong("B", 0L), 9))
        b.append(ubits(d.optLong("C", 0L), 6)); b.append(ubits(d.optLong("D", 0L), 6))
        b.append(ubits(fixType, 4))                              // 0 = undefined
        b.append("00")                                           // spare -> 168 bits
        return sentence(b.toString())
    }

    // Synthetic static for CLASS B vessels: a 24A (name) sentence, plus a
    // 24B only if the payload carries anything worth a Part B. Takes the
    // same store-shaped payload as encodeStaticA — the service picks the
    // encoder based on the vessel's learned class.
    fun encodeStaticBParts(s: JSONObject): List<String> {
        val mmsi = s.optLong("UserID", 0L)
        val out = ArrayList<String>(2)
        out.add(encodeStaticBName(mmsi, s.optString("Name", "")))
        val d = s.optJSONObject("Dimension")
        val type = if (s.has("Type") && !s.isNull("Type")) s.optLong("Type") else null
        val cs = s.optString("CallSign", "").takeIf { it.isNotBlank() }
        if (type != null || cs != null || d != null) {
            out.add(encodeStaticBPartB(mmsi, type, null, cs, d, null))
        }
        return out
    }

    // Relay a real aisstream StaticDataReport (AIS type 24) as the proper
    // AIVDM sentence, content verbatim. PartNumber false = Part A (name),
    // true = Part B (type/vendor/callsign/dims). Returns null if the feed
    // marked the relevant Report part invalid — nothing worth putting on
    // the wire.
    fun encodeStaticDataReport(report: JSONObject): String? {
        val mmsi = report.optLong("UserID", 0L)
        if (!report.optBoolean("PartNumber", false)) {
            val ra = report.optJSONObject("ReportA") ?: return null
            if (!ra.optBoolean("Valid", true)) return null
            return encodeStaticBName(mmsi, ra.optString("Name", ""))
        }
        val rb = report.optJSONObject("ReportB") ?: return null
        if (!rb.optBoolean("Valid", true)) return null
        val type = if (rb.has("ShipType") && !rb.isNull("ShipType"))
            rb.optLong("ShipType") else null
        val fix = if (rb.has("FixType") && !rb.isNull("FixType"))
            rb.optLong("FixType") else null
        val cs = rb.optString("CallSign", "").takeIf { it.isNotBlank() }
        val vendor = rb.optString("VendorIDName", "").takeIf { it.isNotBlank() }
        return encodeStaticBPartB(mmsi, type, vendor, cs,
            rb.optJSONObject("Dimension"), fix)
    }

    fun cleanName(raw: String?): String {
        val s = (raw ?: "").replace("@", " ")
        val joined = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (joined.isEmpty()) "UNKNOWN" else joined
    }

    // Name dedupe + resend pacing — same semantics as the Python bridge.
    // "UNKNOWN" names are never broadcast: nothing is sent and nothing is
    // cached, so the first real name still triggers an immediate send.
    fun shouldSendName(mmsi: Long, name: String): Boolean {
        if (name == "UNKNOWN") return false
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = nameCache[mmsi]
        if (prev == null || prev.first + NAME_RESEND_MS < now || prev.second != name) {
            nameCache[mmsi] = now to name
            return true
        }
        return false
    }

    fun cachedName(mmsi: Long): String? = nameCache[mmsi]?.second

    /**
     * Rate-limit for synthesized static frames: true the first time we're
     * asked about an MMSI this session, and again every SYNTH_RESEND_MS.
     * Mirrors shouldSendName's pacing so name + synthetic static stay in
     * lockstep.
     */
    fun shouldSynthStatic(mmsi: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = synthCache[mmsi]
        if (prev != null && prev + SYNTH_RESEND_MS > now) return false
        synthCache[mmsi] = now
        return true
    }

    // Wipe name history so every vessel's next position report re-sends
    // its name — used to re-populate OsmAnd after it forgets targets.
    // Synth cache cleared alongside: if OsmAnd forgot the vessel, it forgot
    // the synthetic static too.
    fun resetNameCache() {
        nameCache.clear()
        synthCache.clear()
    }
}