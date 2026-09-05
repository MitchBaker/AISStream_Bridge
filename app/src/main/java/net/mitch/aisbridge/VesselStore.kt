package net.mitch.aisbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Persistent vessel registry: MMSI -> static identity only (name, callsign,
 * IMO, ship type, dimensions, draught) plus the learned AIS class ('A'/'B').
 * The store is a LEARNING cache, not a merge stage: real feed messages are
 * relayed verbatim for encoding, and cached data is ONLY ever used to
 * synthesize static frames for vessels whose static data hasn't arrived
 * this session. Volatile data (position, SOG/COG, heading, status,
 * destination, ETA) is not stored.
 *
 * Static-data sources, per class:
 *   Class A: ShipStaticData (type 5) — callsign, IMO, type, dims, draught.
 *   Class B: StaticDataReport (type 24 A/B) and ExtendedClassBPositionReport
 *            (type 19) — name, callsign, type, dims. Type 19 is a position
 *            report that HAPPENS to carry static cargo; it teaches the store
 *            but does not mark the vessel confirmed.
 * Synthesis is class-aware: 'A' vessels synthesize a type 5, 'B' vessels
 * synthesize a type 24 A(+B) — a type 5 with a class-B MMSI is protocol
 * nonsense.
 *
 * Storage: one JSON snapshot in the app's private files dir, rewritten
 * atomically (write tmp, rename).
 */
class VesselStore(private val ctx: Context) {

    companion object {
        private const val FILE = "vessels.json"
        const val MAX_VESSELS = 1500
        private const val STALE_MS = 24L * 60 * 60 * 1000   // drop unseen ships after 24 h
    }

    class Vessel {
        var name: String? = null
        var callsign: String? = null
        var shipType: Int? = null
        var imo: Long? = null
        var maxDraught: Double? = null
        var dimA: Int? = null; var dimB: Int? = null
        var dimC: Int? = null; var dimD: Int? = null
        /** Learned AIS class: 'A' (types 1/2/3/5/27) or 'B' (18/19/24). Write-once. */
        var vesselClass: Char? = null
        var lastSeenMs: Long = 0
    }

    private val vessels = LinkedHashMap<Long, Vessel>()

    // SESSION-SCOPED (never persisted): MMSIs that produced a REAL static
    // report (type 5 or type 24) since this service instance started.
    // Synthesis stays silent for confirmed vessels until the service
    // restarts. Note: type 19 deliberately does NOT confirm — it's a
    // position report that carries static cargo, and its appearance says
    // nothing about whether a real type 24 will follow.
    private val confirmedThisSession = HashSet<Long>()

    val size: Int get() = synchronized(vessels) { vessels.size }

    private fun orNew(mmsi: Long): Vessel =
        vessels.getOrPut(mmsi) { Vessel() }

    /**
     * True if we hold at least one piece of static identity worth keeping.
     * Vessels with none of these (position sightings that never yielded a
     * name or any static detail) are dead weight — they can't feed
     * staticFor(), syntheticStaticFor(), or recentWithName(), so they don't
     * deserve a slot in the persisted snapshot or the MAX_VESSELS budget.
     */
    private fun hasStaticInfo(v: Vessel): Boolean =
        v.name != null || v.callsign != null || v.imo != null ||
                v.shipType != null || v.maxDraught != null ||
                v.dimA != null || v.dimB != null || v.dimC != null || v.dimD != null

    // ------------------------------------------------------------ input

    /** Learn a name; never downgrade a known name to UNKNOWN (MetaData hiccups). */
    fun recordName(mmsi: Long, raw: String?) {
        val n = AisEncoder.cleanName(raw)
        if (n == "UNKNOWN") return
        synchronized(vessels) { orNew(mmsi).name = n }
    }

    /**
     * Note a sighting (position report seen for this MMSI). Position data
     * itself is volatile and NOT stored — only the name refresh and the
     * last-seen timestamp matter. Signature kept so callers don't change.
     */
    fun updatePosition(mmsi: Long, @Suppress("UNUSED_PARAMETER") lat: Double?,
                       @Suppress("UNUSED_PARAMETER") lon: Double?,
                       @Suppress("UNUSED_PARAMETER") sog: Double,
                       @Suppress("UNUSED_PARAMETER") cog: Double?,
                       @Suppress("UNUSED_PARAMETER") hdg: Int?,
                       @Suppress("UNUSED_PARAMETER") status: Int?, name: String?) {
        synchronized(vessels) {
            val v = orNew(mmsi)
            if (name != null && name != "UNKNOWN") v.name = name
            v.lastSeenMs = System.currentTimeMillis()
        }
    }

    /**
     * Learned class access + write-once tagging. A vessel's class is a
     * physical property of its transponder; first evidence wins and is
     * never overwritten (avoids flap on oddball feed mixes).
     */
    fun vesselClass(mmsi: Long): Char? =
        synchronized(vessels) { vessels[mmsi]?.vesselClass }

    fun tagClass(mmsi: Long, cls: Char) {
        synchronized(vessels) {
            val v = orNew(mmsi)
            if (v.vesselClass == null) v.vesselClass = cls
        }
    }

    /**
     * LEARN from a real ShipStaticData message (type 5) — nothing is
     * returned and nothing is merged into the relay. The service passes
     * the ORIGINAL message to the encoder verbatim; this method exists
     * purely to (a) update the cached identity fields, (b) tag the vessel
     * Class A, and (c) mark it confirmed for this session, which silences
     * synthesis for its MMSI.
     */
    fun absorbStatic(mmsi: Long, msg: JSONObject, metaName: String) {
        synchronized(vessels) {
            val v = orNew(mmsi)
            if (v.vesselClass == null) v.vesselClass = 'A'
            confirmedThisSession.add(mmsi)
            if (msg.has("ImoNumber") && !msg.isNull("ImoNumber") &&
                msg.optLong("ImoNumber") > 0) v.imo = msg.optLong("ImoNumber")
            msg.optString("CallSign").trim().takeIf { it.isNotEmpty() }?.let { v.callsign = it }
            if (msg.has("Type") && !msg.isNull("Type")) v.shipType = msg.optInt("Type")
            if (msg.has("MaximumStaticDraught") && !msg.isNull("MaximumStaticDraught"))
                v.maxDraught = msg.optDouble("MaximumStaticDraught")
            msg.optJSONObject("Dimension")?.let { d ->
                if (d.has("A") && !d.isNull("A")) v.dimA = d.optInt("A")
                if (d.has("B") && !d.isNull("B")) v.dimB = d.optInt("B")
                if (d.has("C") && !d.isNull("C")) v.dimC = d.optInt("C")
                if (d.has("D") && !d.isNull("D")) v.dimD = d.optInt("D")
            }
            // Name priority for the CACHE: live meta name > message name > stored.
            val cand = metaName.takeIf { it != "UNKNOWN" }
                ?: msg.optString("Name").let { AisEncoder.cleanName(it) }.takeIf { it != "UNKNOWN" }
            if (cand != null) v.name = cand
            v.lastSeenMs = System.currentTimeMillis()
        }
    }

    /**
     * LEARN the static cargo of a type-19 Extended Class B position report
     * (name, type, dimensions). Does NOT confirm the vessel — type 19 is a
     * position report, and synthesis may still be needed to fill gaps a
     * subsequent real type 24 would cover. The service also funnels the
     * meta-name / last-seen through updatePosition(); this absorbs what
     * only the message body carries.
     */
    fun absorbType19(mmsi: Long, msg: JSONObject, metaName: String) {
        synchronized(vessels) {
            val v = orNew(mmsi)
            if (v.vesselClass == null) v.vesselClass = 'B'
            if (msg.has("Type") && !msg.isNull("Type")) v.shipType = msg.optInt("Type")
            msg.optJSONObject("Dimension")?.let { d ->
                if (d.has("A") && !d.isNull("A")) v.dimA = d.optInt("A")
                if (d.has("B") && !d.isNull("B")) v.dimB = d.optInt("B")
                if (d.has("C") && !d.isNull("C")) v.dimC = d.optInt("C")
                if (d.has("D") && !d.isNull("D")) v.dimD = d.optInt("D")
            }
            // Name priority: meta name > message name > stored.
            val cand = metaName.takeIf { it != "UNKNOWN" }
                ?: msg.optString("Name").let { AisEncoder.cleanName(it) }.takeIf { it != "UNKNOWN" }
            if (cand != null) v.name = cand
            v.lastSeenMs = System.currentTimeMillis()
        }
    }

    /**
     * LEARN from a real StaticDataReport (AIS type 24) — the actual static
     * report for Class B vessels. Part A carries the name; Part B carries
     * ship type, vendor, callsign, dimensions. Tags Class B and marks the
     * vessel CONFIRMED for this session (either part counts — a station
     * that sent one part is on air with its static cycle).
     */
    fun absorbStaticReport(mmsi: Long, report: JSONObject, metaName: String) {
        synchronized(vessels) {
            val v = orNew(mmsi)
            if (v.vesselClass == null) v.vesselClass = 'B'
            confirmedThisSession.add(mmsi)
            report.optJSONObject("ReportA")?.let { ra ->
                if (ra.optBoolean("Valid", true)) {
                    val cand = metaName.takeIf { it != "UNKNOWN" }
                        ?: ra.optString("Name").let { AisEncoder.cleanName(it) }
                            .takeIf { it != "UNKNOWN" }
                    if (cand != null) v.name = cand
                }
            }
            report.optJSONObject("ReportB")?.let { rb ->
                if (!rb.optBoolean("Valid", true)) return@let
                if (rb.has("ShipType") && !rb.isNull("ShipType")) v.shipType = rb.optInt("ShipType")
                rb.optString("CallSign").trim().takeIf { it.isNotEmpty() }?.let { v.callsign = it }
                rb.optJSONObject("Dimension")?.let { d ->
                    if (d.has("A") && !d.isNull("A")) v.dimA = d.optInt("A")
                    if (d.has("B") && !d.isNull("B")) v.dimB = d.optInt("B")
                    if (d.has("C") && !d.isNull("C")) v.dimC = d.optInt("C")
                    if (d.has("D") && !d.isNull("D")) v.dimD = d.optInt("D")
                }
            }
            v.lastSeenMs = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ output

    /**
     * Type-5-shaped payload built from cache — used for SYNTHESIS only:
     * filling in a static card for a vessel whose real static data hasn't
     * arrived this session, and for preload replay after a restart. Null if
     * we lack a name or have nothing beyond a name (type-24A covers
     * name-only). Class-A and Class-B callers shape this differently at the
     * encoder — check vesselClass() to pick.
     */
    fun staticFor(mmsi: Long): JSONObject? = synchronized(vessels) {
        val v = vessels[mmsi] ?: return null
        if (v.name == null) return null
        val rich = v.callsign != null || v.shipType != null || v.dimA != null ||
                v.maxDraught != null || v.imo != null
        if (!rich) return null
        val out = JSONObject()
        out.put("UserID", mmsi)
        out.put("Name", v.name!!)
        v.imo?.let { out.put("ImoNumber", it) }
        v.callsign?.let { out.put("CallSign", it) }
        v.shipType?.let { out.put("Type", it) }
        v.maxDraught?.let { out.put("MaximumStaticDraught", it) }
        if (v.dimA != null || v.dimB != null || v.dimC != null || v.dimD != null) {
            val d = JSONObject()
            v.dimA?.let { d.put("A", it) }; v.dimB?.let { d.put("B", it) }
            v.dimC?.let { d.put("C", it) }; v.dimD?.let { d.put("D", it) }
            out.put("Dimension", d)
        }
        out
    }

    /** Synthetic static for the live path; null when confirmed this session. */
    fun syntheticStaticFor(mmsi: Long): JSONObject? = synchronized(vessels) {
        if (mmsi in confirmedThisSession) return null
        staticFor(mmsi)
    }

    /** Real static data (type 5 or 24) received since this session began? */
    fun staticConfirmed(mmsi: Long): Boolean =
        synchronized(vessels) { mmsi in confirmedThisSession }

    /** Vessels with a real name seen within the window, newest first. */
    fun recentWithName(windowMs: Long): List<Pair<Long, String>> = synchronized(vessels) {
        val cutoff = System.currentTimeMillis() - windowMs
        vessels.entries
            .filter { it.value.lastSeenMs >= cutoff && it.value.name != null }
            .sortedByDescending { it.value.lastSeenMs }
            .map { it.key to it.value.name!! }
    }

    fun displayName(mmsi: Long): String? = synchronized(vessels) { vessels[mmsi]?.name }

    // ------------------------------------------------------------ persistence

    fun load(): Int {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return 0
        return try {
            val arr = JSONArray(f.readText())
            synchronized(vessels) {
                vessels.clear()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        val v = fromDisk(o)
                        // Skip records with no usable static identity. Legacy
                        // snapshots contained position-only vessels; loading
                        // them would recreate the dead-weight problem on the
                        // very next boot. They fall out permanently once the
                        // first post-load save() rewrites the file.
                        if (!hasStaticInfo(v)) continue
                        vessels[o.getLong("mmsi")] = v
                    } catch (_: Exception) { /* skip malformed record */ }
                }
                vessels.size
            }
        } catch (_: Exception) { 0 }
    }

    /**
     * REPLACE the entire store from an exported snapshot. Same schema as
     * the on-disk vessels.json (legacy keys tolerated and ignored by
     * fromDisk). Records lacking static identity are skipped, same as
     * load(). Throws JSONException on bad input — caller handles logging.
     * Returns the resulting store size.
     */
    fun importSnapshot(json: String): Int {
        val arr = JSONArray(json)                 // throws on bad input
        synchronized(vessels) {
            vessels.clear()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val v = fromDisk(o)
                    if (!hasStaticInfo(v)) continue
                    vessels[o.getLong("mmsi")] = v
                } catch (_: Exception) { /* skip malformed record */ }
            }
            return vessels.size
        }
    }

    /**
     * Wipe everything: memory, session-scoped confirmed state, and the
     * on-disk snapshot (plus any stray tmp file from a crashed save).
     * Returns how many vessels were resident before the wipe.
     */
    fun clear(): Int = synchronized(vessels) {
        val n = vessels.size
        vessels.clear()
        confirmedThisSession.clear()
        File(ctx.filesDir, FILE).delete()
        File(ctx.filesDir, "$FILE.tmp").delete()
        n
    }

    fun save() {
        val text = synchronized(vessels) {
            prune()
            val arr = JSONArray()
            for ((mmsi, v) in vessels) arr.put(toDisk(mmsi, v))
            arr.toString()
        }
        try {
            val dir = ctx.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(text)
            val dst = File(dir, FILE)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        } catch (_: Exception) { /* best effort */ }
    }

    fun saveAsync(pool: Executor) {
        try { pool.execute { save() } } catch (_: RejectedExecutionException) {}
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        // Evict the stale AND the empty in one sweep: anything past the
        // 24 h window, or holding no static identity at all. Empty entries
        // are created by updatePosition() for every anonymous position
        // report, so on busy water this is what keeps the in-memory map
        // honest between snapshot writes — and MAX_VESSELS evictions now
        // only ever displace vessels that actually carry identity.
        vessels.values.removeAll { it.lastSeenMs < cutoff || !hasStaticInfo(it) }
        if (vessels.size > MAX_VESSELS) {
            val excess = vessels.size - MAX_VESSELS
            val oldest = vessels.entries.sortedBy { it.value.lastSeenMs }
            for (i in 0 until excess) vessels.remove(oldest[i].key)
        }
    }

    // ------------------------------------------------------------ JSON plumbing

    private fun toDisk(mmsi: Long, v: Vessel): JSONObject {
        val o = JSONObject()
        o.put("mmsi", mmsi)
        o.put("seen", v.lastSeenMs)
        v.name?.let { o.put("name", it) }
        v.callsign?.let { o.put("callsign", it) }
        v.shipType?.let { o.put("type", it) }
        v.imo?.let { o.put("imo", it) }
        v.maxDraught?.let { o.put("draught", it) }
        v.dimA?.let { o.put("a", it) }; v.dimB?.let { o.put("b", it) }
        v.dimC?.let { o.put("c", it) }; v.dimD?.let { o.put("d", it) }
        v.vesselClass?.let { o.put("cls", it.toString()) }
        return o
    }

    // Legacy keys (lat/lon/sog/cog/hdw/status/dest/em/ed/eh/en/conf) in
    // existing vessels.json snapshots are ignored — dropped on next save().
    private fun fromDisk(o: JSONObject): Vessel {
        val v = Vessel()
        fun s(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
        fun i(k: String): Int? = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
        fun d(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k) else null
        v.name = s("name"); v.callsign = s("callsign")
        v.shipType = i("type"); v.dimA = i("a"); v.dimB = i("b"); v.dimC = i("c"); v.dimD = i("d")
        v.imo = if (o.has("imo") && !o.isNull("imo")) o.optLong("imo") else null
        v.maxDraught = d("draught")
        v.vesselClass = s("cls")?.getOrNull(0)
        v.lastSeenMs = o.optLong("seen", 0)
        return v
    }
}