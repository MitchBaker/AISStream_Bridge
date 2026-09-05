package net.mitch.aisbridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug capture (v1.6): pairs each raw aisstream frame with the NMEA sentences
 * it produced (or the reason it produced none), plus connection events,
 * settings snapshot/changes, and rate notes. One JSON file per capture,
 * written to cache/exports/. Never records the API key.
 */
class DebugCapture(private val appContext: Context) {

    companion object {
        private const val TAG = "DebugCapture"
        private const val MAX_RECORDS = 5000     // inbound messages retained
        private const val MAX_EVENTS = 2000      // conn/settings/note events
        private const val MAX_RAW_LEN = 2048     // chars of raw JSON kept per frame

        /** Active capture, if any. Safe to read from any thread. */
        @Volatile
        var active: DebugCapture? = null
            private set

        val isActive: Boolean get() = active != null

        /** Record a settings change made while a capture is running. Any thread. */
        fun settingsChanged(field: String, oldValue: String?, newValue: String?) {
            active?.addEvent(
                JSONObject()
                    .put("type", "settings")
                    .put("field", field)
                    .put("from", oldValue ?: "")
                    .put("to", newValue ?: "")
            )
        }

        /** End the active capture (manual stop, expiry, or service stop). */
        fun stop(appContext: Context, reason: String): File? = active?.stop(reason)
    }

    private val lock = Any()
    private val messages = ArrayList<JSONObject>()
    private val events = ArrayList<JSONObject>()

    private var seqCounter = 0L
    private var startedAtMs = 0L
    private var plannedMs = 0L
    private lateinit var settingsSnapshot: JSONObject
    private var metaSnapshot: JSONObject = JSONObject()

    // current in-flight message record (raw frame awaiting its sentences)
    private var currentSeq = 0L
    private var currentRaw: String? = null
    private var currentOut = ArrayList<String>()
    private var currentDrop: String? = null

    @Volatile private var finished = false
    private var truncatedMessages = 0
    private var truncatedEvents = 0

    // ---- lifecycle ----------------------------------------------------------

    fun start(durationMinutes: Int, settings: JSONObject, meta: JSONObject) {
        startedAtMs = System.currentTimeMillis()
        plannedMs = durationMinutes * 60_000L
        settingsSnapshot = JSONObject(settings.toString())
        metaSnapshot = JSONObject(meta.toString())
        active = this
        note("capture started (${durationMinutes} min)")
        Log.i("DebugCapture", "capture started, ${durationMinutes} min")
    }

    /** Ends the capture and writes the file. Calling twice is a safe no-op. */
    fun stop(reason: String): File? {
        synchronized(lock) {
            if (finished) return null
            finished = true
            flushCurrentLocked()
        }
        active = null
        return write(reason)
    }

    private fun write(reason: String): File? {
        return try {
            val doc = JSONObject()
            doc.put("captureVersion", 1)
            doc.put("startedAt", startedAtMs)
            doc.put("plannedDurationMs", plannedMs)
            doc.put("actualDurationMs", System.currentTimeMillis() - startedAtMs)
            doc.put("endedBy", reason)
            doc.put("truncatedMessages", truncatedMessages)
            doc.put("truncatedEvents", truncatedEvents)
            doc.put("meta", metaSnapshot)
            doc.put("settings", settingsSnapshot)
            synchronized(lock) {
                val evts = JSONArray()
                events.forEach { evts.put(it) }
                doc.put("events", evts)
                val msgs = JSONArray()
                messages.forEach { msgs.put(it) }
                doc.put("messages", msgs)
            }

            val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "debug-$stamp.json")
            FileWriter(out).use { it.write(doc.toString(2)) }
            Log.i("DebugCapture", "saved ${out.name}")
            out
        } catch (e: Exception) {
            Log.e("DebugCapture", "failed to write capture file", e)
            null
        }
    }

    // ---- per-message recording (WebSocket reader thread) ---------------------

    /** Call once per raw frame received, BEFORE parsing. */
    fun beginMessage(raw: String) {
        synchronized(lock) {
            flushCurrentLocked()
            currentSeq = ++seqCounter
            currentRaw = if (raw.length > MAX_RAW_LEN) {
                truncatedMessages++
                raw.substring(0, MAX_RAW_LEN) + "...[truncated, ${raw.length} chars]"
            } else {
                raw
            }
        }
    }

    /** Attach an NMEA sentence produced from the current message. */
    fun attachSentence(sentence: String) {
        synchronized(lock) {
            if (currentRaw != null) currentOut.add(sentence)
        }
    }

    /** Mark the current message as producing no output, with the reason. */
    fun dropCurrent(reason: String) {
        synchronized(lock) {
            if (currentRaw != null && currentDrop == null) currentDrop = reason
        }
    }

    private fun flushCurrentLocked() {
        val raw = currentRaw ?: return
        if (messages.size >= MAX_RECORDS) {
            truncatedMessages++
        } else {
            val rec = JSONObject()
                .put("seq", currentSeq)
                .put("t", System.currentTimeMillis())
                .put("raw", raw)
                .put("out", JSONArray(currentOut))
            if (currentDrop != null) rec.put("dropped", currentDrop)
            messages.add(rec)
        }
        currentRaw = null
        currentOut = ArrayList()
        currentDrop = null
    }

    // ---- event recording (any thread) ----------------------------------------

    fun note(text: String) = addEvent(JSONObject().put("type", "note").put("text", text))

    fun connEvent(event: String, detail: JSONObject.() -> Unit = {}) {
        addEvent(JSONObject().put("type", "conn").put("event", event).apply(detail))
    }

    private fun addEvent(evt: JSONObject) {
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                truncatedEvents++
            } else {
                events.add(evt.put("t", System.currentTimeMillis()))
            }
        }
    }
}