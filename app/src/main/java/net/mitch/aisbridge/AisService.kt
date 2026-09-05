package net.mitch.aisbridge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AisService : Service() {

    companion object {
        private const val WS_URL = "wss://stream.aisstream.io/v0/stream"
        private const val CHANNEL_ID = "ais_bridge_channel"
        const val ACTION_STOP = "net.mitch.aisbridge.STOP"
        const val ACTION_RELOAD = "net.mitch.aisbridge.RELOAD"
        const val ACTION_START_CAPTURE = "net.mitch.aisbridge.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "net.mitch.aisbridge.STOP_CAPTURE"
        // Store management. IMPORT carries the staged snapshot's absolute
        // file path (content never rides an Intent extra — no 1 MB binder
        // limit exposure regardless of MAX_VESSELS).
        const val ACTION_IMPORT = "net.mitch.aisbridge.IMPORT"
        const val ACTION_CLEAR_STORE = "net.mitch.aisbridge.CLEAR_STORE"
        const val UDP_PORT = 10110
        @Volatile var running = false

        // v1.7: visible to SettingsActivity so the capture button shows the
        // correct label for a capture that is armed but not yet hot (feed
        // down). Cleared when the capture goes hot, when canceled before
        // start, or on service destroy.
        @Volatile var captureArmed = false

        private const val SAVE_INTERVAL_MS = 30_000L           // snapshot cadence
        private const val PRELOAD_WINDOW_MS = 30L * 60 * 1000  // static replay window
        private const val PRELOAD_BATCH = 10                   // vessels per burst
        private const val PRELOAD_PERIOD_MS = 400L             // between bursts
    }

    // All five vessel-data message types, now that the encoder has proper
    // paths for each. (Type 19 previously coerced to a mangled type 1 and
    // StaticDataReport was never subscribed — both fixed.)
    private var msgTypes: MutableSet<String> = mutableSetOf(
        "PositionReport", "StandardClassBPositionReport",
        "ExtendedClassBPositionReport", "LongRangeAisBroadcastMessage",
        "ShipStaticData", "StaticDataReport")
    private var skipAnchor = false
    private var skipMoored = false
    private var skipZeroSog = false
    private var rewriteStale = false

    private var apiKey = ""
    private var spanDeg = 40.0 / 60.0
    private var useGps = true

    private var ws: WebSocket? = null
    private var udp: DatagramSocket? = null
    private var locMgr: LocationManager? = null
    private var curLat: Double? = null
    private var curLon: Double? = null
    private var lastSubSent = 0L
    private var backoff = 2
    private var statusKeyChecked = false
    private var store: VesselStore? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // ---- debug capture state (v1.6/v1.7) ----
    // capture/msgsThisMinute are written from OkHttp's WebSocket callback
    // thread (via handleMessage) and read/written from the main thread (via
    // rateTick/stopCapture); @Volatile keeps those cross-thread reads honest.
    @Volatile private var capture: DebugCapture? = null
    private var captureExpiry: Runnable? = null
    @Volatile private var msgsThisMinute = 0L
    // v1.7: capture can be ARMED from Settings before the feed runs. It goes
    // hot only when the feed starts, BEFORE connectWs(), so the capture sees
    // the full connect/subscribe/confirmation handshake. Null = not armed.
    private var armedCaptureMinutes: Int? = null

    private val rateTick = object : Runnable {
        override fun run() {
            capture?.note("$msgsThisMinute msgs/min")
            msgsThisMinute = 0
            handler.postDelayed(this, 60_000L)
        }
    }

    private val saverRunnable = object : Runnable {
        override fun run() {
            store?.saveAsync(io)
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val oldLat = curLat
            val oldLon = curLon
            curLat = loc.latitude
            curLon = loc.longitude
            // Resubscribe only when we've drifted more than half the box.
            val moved = oldLat == null || oldLon == null ||
                    kotlin.math.abs(oldLat - loc.latitude) > spanDeg / 2 ||
                    kotlin.math.abs(oldLon - loc.longitude) > spanDeg / 2
            if (moved) sendSubscription(force = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            // Live refresh of client-side settings. If the message-type set
            // changed AND we're streaming, also resubscribe so the server
            // stops/starts sending those types. Connection is left intact.
            // NOTE: must be delivered via startService(), not sendBroadcast()
            // — broadcasts never reach onStartCommand of a Service.
            // START_NOT_STICKY so a system restart never resurrects us
            // through this branch (a restart delivers a null intent anyway,
            // but the flag hygiene matters for consistency).
            val oldTypes = msgTypes.toSet()
            loadFilters(getSharedPreferences("aisbridge", MODE_PRIVATE))
            if (capture != null) {
                // Journal the change so the capture file shows which
                // messages came before/after the settings shift.
                if (msgTypes != oldTypes) {
                    DebugCapture.settingsChanged(
                        "messageTypes",
                        oldTypes.sorted().joinToString(","),
                        msgTypes.sorted().joinToString(","))
                }
                DebugCapture.settingsChanged(
                    "skipAnchor/skipMoored/skipZeroSog/rewriteStale",
                    null, "see note")
                capture?.note("settings reloaded (anchor=$skipAnchor, " +
                        "moored=$skipMoored, zeroSog=$skipZeroSog, " +
                        "rewriteStale=$rewriteStale)")
            }
            if (running && ws != null && msgTypes != oldTypes) {
                LogBuffer.log("[settings reloaded — message types changed, resubscribing]")
                sendSubscription(force = true)
            } else {
                LogBuffer.log("[settings reloaded]")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_CAPTURE) {
            val minutes = (intent.getIntExtra("minutes", 10)).coerceIn(1, 120)
            if (running) {
                // Feed already up: capture goes hot immediately. It cannot
                // see the original handshake (already happened) — that's what
                // arming is for.
                startCapture(minutes)
            } else {
                // v1.7: feed not running yet. ARM the capture and bring
                // the service up as a proper foreground service so Android
                // doesn't kill us while we wait for Start. The capture goes
                // hot in the main start path below, BEFORE connectWs().
                armedCaptureMinutes = minutes
                captureArmed = true
                startAsForeground(captureOnly = true)
                LogBuffer.log("[debug capture: armed, ${minutes} min — " +
                        "will begin when the feed starts]")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_CAPTURE) {
            stopCapture("manual stop")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_IMPORT) {
            // Replace the vessel store from a snapshot staged by
            // SettingsActivity (picked via SAF, copied into cacheDir, passed
            // here by path). Runs whether or not the feed is live; doesn't
            // start the feed.
            val path = intent.getStringExtra("path")
            if (path.isNullOrBlank()) {
                LogBuffer.log("[import failed: no data supplied]")
                return START_NOT_STICKY
            }
            if (store == null) {
                store = VesselStore(this)
                store?.load()
            }
            val s = store!!
            io.execute {
                try {
                    val json = File(path).readText()
                    File(path).delete()        // staged copy — don't leave it lying around
                    val n = s.importSnapshot(json)
                    s.save()                   // bake it to disk immediately
                    // Fresh cache state so the imported identities actually
                    // replay: without this, a warm process would keep
                    // throttling names that the new snapshot reintroduced.
                    AisEncoder.resetNameCache()
                    handler.post { LogBuffer.log("[import complete: $n vessels]") }
                } catch (e: Exception) {
                    handler.post { LogBuffer.log("[import failed: ${e.message}]") }
                }
            }
            if (!running) stopSelf()   // tidy: don't linger as an idle service
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLEAR_STORE) {
            // Wipe the vessel store (memory + disk + session confirmed set).
            // Doesn't touch the feed; in-flight vessels repopulate within
            // seconds and re-confirm via real static reports as they arrive.
            val s = store ?: VesselStore(this).also { store = it; it.load() }
            val n = s.clear()
            AisEncoder.resetNameCache()
            LogBuffer.log("[vessel store cleared: $n vessels wiped]")
            if (!running) stopSelf()   // tidy: don't linger as an idle service
            return START_NOT_STICKY
        }

        running = true
        startAsForeground()

        val prefs = getSharedPreferences("aisbridge", MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""
        val nm = prefs.getString("span_nm", "40")?.toDoubleOrNull() ?: 40.0
        spanDeg = nm / 60.0
        useGps = (prefs.getString("source", "gps") ?: "gps") != "manual"
        loadFilters(prefs)
        statusKeyChecked = false                    // re-log keys once per feed start

        // Persistent vessel registry: load static identity history, snapshot
        // periodically, and replay known vessels' static cards to OsmAnd so
        // it shows real names and full ship cards immediately after a
        // restart.
        store = VesselStore(this)
        val loaded = store?.load() ?: 0
        LogBuffer.log("[vessel store: $loaded vessels loaded]")

        // New session: clear the name/synth throttle caches so every
        // vessel's first position report re-sends name and synthetic
        // static promptly, even if the app process survived the previous
        // bridge run (the caches are singletons that don't die with the
        // service instance).
        AisEncoder.resetNameCache()

        handler.postDelayed(saverRunnable, SAVE_INTERVAL_MS)

        udp = DatagramSocket()
        startLocation()

        // v1.7: if a capture was armed from Settings, go hot NOW — after
        // settings are loaded (so the snapshot reflects reality) and BEFORE
        // connectWs(), so the capture records the websocket open, subscribe
        // send, SubscriptionConfirmation (with CompressionEnabled), and every
        // frame from the very first byte.
        //
        // Clear the arm state BEFORE calling startCapture(): startCapture()
        // begins with stopCapture("superseded"), and if armedCaptureMinutes
        // is still non-null at that point, stopCapture's "nothing to stop"
        // branch fires first and logs a bogus "canceled before start"
        // message immediately ahead of the real "started" log line.
        val minutesToStart = armedCaptureMinutes
        armedCaptureMinutes = null
        captureArmed = false
        minutesToStart?.let { startCapture(it) }

        connectWs()
        LogBuffer.log("[started: box +-${nm.toInt()} nm, UDP 127.0.0.1:$UDP_PORT, " +
                "${msgTypes.size} message types]")
        schedulePreload()
        return START_STICKY
    }

    // ---- debug capture control (v1.6/v1.7) ----

    private fun startCapture(minutes: Int) {
        stopCapture("superseded")
        val settings = JSONObject().apply {
            put("messageTypes", msgTypes.sorted().joinToString(","))
            put("skipAnchor", skipAnchor)
            put("skipMoored", skipMoored)
            put("skipZeroSog", skipZeroSog)
            put("rewriteStaleStatus", rewriteStale)
            put("source", if (useGps) "gps" else "manual")
            put("spanNm", (spanDeg * 60).toInt())
            put("udpHost", "127.0.0.1")
            put("udpPort", UDP_PORT)
            // API key deliberately excluded.
        }
        val meta = JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("sdkInt", Build.VERSION.SDK_INT)
            //put("storeVessels", store?.size ?: 0)
        }
        val c = DebugCapture(applicationContext)
        c.start(minutes, settings, meta)
        capture = c
        msgsThisMinute = 0
        handler.postDelayed(rateTick, 60_000L)
        val expiry = Runnable { stopCapture("duration elapsed") }
        captureExpiry = expiry
        handler.postDelayed(expiry, minutes * 60_000L)
        LogBuffer.log("[debug capture: started, ${minutes} min]")
    }

    private fun stopCapture(reason: String) {
        // v1.7: an armed capture that never went hot has recorded nothing and
        // written no file — just clear the arm state and say so.
        if (capture == null) {
            if (armedCaptureMinutes != null) {
                armedCaptureMinutes = null
                captureArmed = false
                LogBuffer.log("[debug capture: canceled before start ($reason)]")
            }
            return
        }
        handler.removeCallbacks(rateTick)
        captureExpiry?.let { handler.removeCallbacks(it) }
        captureExpiry = null
        val c = capture ?: return
        capture = null
        val f = c.stop(reason)
        LogBuffer.log(if (f != null) "[debug capture: saved ${f.name}]"
        else "[debug capture: ended ($reason)]")
    }

    // Replay known vessels' static cards to OsmAnd in staggered batches:
    // type-5 static (Class A) or type-24A name / 24A+B (Class B), matching
    // the vessel's learned class. Positions are not cached, so vessels
    // appear on the map only after the feed sends a fresh position report —
    // preload restores identities only. Bounded rate so we don't blast
    // hundreds of datagrams in one gulp; log volume is suppressed so the
    // window isn't drowned (mirror = false).
    private fun schedulePreload() {
        val s = store ?: return
        val list = s.recentWithName(PRELOAD_WINDOW_MS)
        if (list.isEmpty()) return
        LogBuffer.log("[preloading ${list.size} known vessels...]")
        var idx = 0
        val pump = object : Runnable {
            override fun run() {
                var n = 0
                while (idx < list.size && n < PRELOAD_BATCH) {
                    val (mmsi, name) = list[idx++]; n++
                    val st = s.staticFor(mmsi)
                    if (st != null) {
                        val lines = if (s.vesselClass(mmsi) == 'B')
                            AisEncoder.encodeStaticBParts(st)
                        else AisEncoder.encodeStaticA(st)
                        for (line in lines) send(line, mirror = false)
                    } else {
                        send(AisEncoder.encodeStaticBName(mmsi, name), mirror = false)
                    }
                }
                if (idx < list.size) handler.postDelayed(this, PRELOAD_PERIOD_MS)
                else LogBuffer.log("[preload complete: $idx vessels]")
            }
        }
        handler.postDelayed(pump, 1500L)   // let OsmAnd's socket settle first
    }

    private fun loadFilters(prefs: android.content.SharedPreferences) {
        // One-time migration (v1.8): StaticDataReport is newly supported.
        // A saved msg_types set from an older build won't contain it, and
        // saved sets beat defaults — union it in once, then set the flag.
        // (Deliberately does NOT force-include ExtendedClassBPositionReport:
        // that checkbox existed before, so an unticked box is a user
        // decision, not a gap.)
        if (!prefs.getBoolean("mig_static_data_report_v18", false)) {
            val saved = prefs.getStringSet("msg_types", null)
            if (saved != null && "StaticDataReport" !in saved) {
                prefs.edit().putStringSet("msg_types", saved + "StaticDataReport").apply()
            }
            prefs.edit().putBoolean("mig_static_data_report_v18", true).apply()
        }
        msgTypes = (prefs.getStringSet("msg_types", msgTypes) ?: msgTypes).toMutableSet()
        skipAnchor = prefs.getBoolean("skip_anchor", false)
        skipMoored = prefs.getBoolean("skip_moored", false)
        skipZeroSog = prefs.getBoolean("skip_zero_sog", false)
        rewriteStale = prefs.getBoolean("rewrite_stale_status", false)
        if (msgTypes.isEmpty()) {
            msgTypes = setOf("PositionReport", "StandardClassBPositionReport",
                "ExtendedClassBPositionReport", "LongRangeAisBroadcastMessage",
                "ShipStaticData", "StaticDataReport").toMutableSet()
        }
    }

    // aisstream names the field "NavigationalStatus" in PositionReport;
    // accept "Status" too defensively for other message schemas.
    private fun navStatus(inner: JSONObject): Int? =
        if (inner.has("NavigationalStatus") && !inner.isNull("NavigationalStatus"))
            inner.optInt("NavigationalStatus")
        else if (inner.has("Status") && !inner.isNull("Status"))
            inner.optInt("Status")
        else null

    private fun startAsForeground(captureOnly: Boolean = false) {
        val nmgr = getSystemService(NotificationManager::class.java)
        nmgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AIS Bridge", NotificationManager.IMPORTANCE_LOW))

        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (captureOnly) "AIS Bridge — debug capture armed"
            else "AIS Bridge running")
            .setContentText(if (captureOnly)
                "Debug capture armed; waiting for feed start"
            else "Streaming vessels to OsmAnd on UDP $UDP_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notif)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!useGps) {
            // Manual mode: fixed center from settings, no GPS polling.
            val prefs = getSharedPreferences("aisbridge", MODE_PRIVATE)
            curLat = prefs.getString("manual_lat", "")?.toDoubleOrNull()
            curLon = prefs.getString("manual_lon", "")?.toDoubleOrNull()
            if (curLat == null || curLon == null) {
                LogBuffer.log("[ERROR: manual mode but lat/lon missing or invalid]")
            } else {
                LogBuffer.log("[using manual center: $curLat, $curLon]")
            }
            return
        }
        locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            locMgr?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true -> LocationManager.GPS_PROVIDER
            locMgr?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
        // Callbacks bind to the calling thread's Looper — the main thread
        // here (onStartCommand), so an explicit Looper arg is unnecessary
        // and its overload isn't resolvable in this compile SDK anyway.
        locMgr?.requestLocationUpdates(provider, 5000L, 0f, locationListener)
        try {
            val last = locMgr?.getLastKnownLocation(provider)
            if (last != null) { curLat = last.latitude; curLon = last.longitude }
        } catch (_: SecurityException) {}
    }

    private fun subscriptionJson(): String {
        val lat = curLat ?: 0.0
        val lon = curLon ?: 0.0
        val sub = JSONObject()
        sub.put("APIKey", apiKey)
        val box = JSONArray()
            .put(JSONArray().put(lat - spanDeg).put(lon - spanDeg))
            .put(JSONArray().put(lat + spanDeg).put(lon + spanDeg))
        sub.put("BoundingBoxes", JSONArray().put(box))
        sub.put("FilterMessageTypes", JSONArray(msgTypes.toList()))
        return sub.toString()
    }

    private fun connectWs() {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                capture?.connEvent("opened") { put("httpCode", response.code) }
                LogBuffer.log("[connected]")
                backoff = 2
                sendSubscription(force = true)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())       // aisstream frames are binary UTF-8 JSON
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                capture?.connEvent("failure") {
                    put("error", t.message ?: "unknown")
                    put("httpCode", response?.code ?: -1)
                }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                capture?.connEvent("closed") {
                    put("code", code)
                    put("reason", reason)
                }
                scheduleReconnect()
            }
        })
    }

    private fun sendSubscription(force: Boolean) {
        val socket = ws ?: return
        // Both must be resolved — in manual mode lat/lon are parsed
        // independently and either can fail on its own, so checking only
        // curLat left a null-longitude fall through to subscriptionJson()'s
        // "?: 0.0" default and silently subscribed near 0° longitude.
        if (curLat == null || curLon == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSubSent < 1000) return   // max 1 resubscribe/second
        lastSubSent = now
        socket.send(subscriptionJson())
        capture?.connEvent("subscribed") {
            put("boxSpanNm", (spanDeg * 60).toInt())
            put("centerLat", curLat)
            put("centerLon", curLon)
            put("forced", force)
        }
        LogBuffer.log("[subscribed: box +-${(spanDeg * 60).toInt()} nm]")
    }

    private fun scheduleReconnect() {
        if (!running) return
        capture?.connEvent("reconnectScheduled") { put("backoffSec", backoff) }
        LogBuffer.log("[connection lost, retrying in ${backoff}s]")
        handler.postDelayed({ if (running) connectWs() }, backoff * 1000L)
        backoff = minOf(backoff * 2, 60)
    }

    // ---- Python handle_message() port + client-side vessel filters ----
    private fun handleMessage(text: String) {
        // Record the raw frame BEFORE any parsing or filtering — even
        // messages we reject end up in the capture file with the reason.
        msgsThisMinute++
        capture?.beginMessage(text)
        try {
            val data = JSONObject(text)
            if (data.optString("MessageType") == "SubscriptionConfirmation") {
                val compressed = data.optJSONObject("Message")
                    ?.optBoolean("CompressionEnabled", false) ?: false
                capture?.connEvent("subscriptionConfirmed") {
                    put("compressionEnabled", compressed)
                }
                // Compression verdict from the server, surfaced immediately.
                LogBuffer.log("[subscription confirmed — compression " +
                        (if (compressed) "ENABLED" else "DISABLED") + "]")
                capture?.dropCurrent("server control message")
                return
            }
            val meta = data.optJSONObject("MetaData") ?: JSONObject()
            val msgs = data.optJSONObject("Message") ?: JSONObject()

            val lr = msgs.optJSONObject("LongRangeAisBroadcastMessage")
            val isLongRange = lr != null
            val ext19 = msgs.optJSONObject("ExtendedClassBPositionReport")
            val inner = lr
                ?: msgs.optJSONObject("PositionReport")
                ?: msgs.optJSONObject("StandardClassBPositionReport")
                ?: ext19
            if (inner != null) {
                // Belt-and-braces: the subscription already filters server-side.
                val typeKey = when {
                    lr != null -> "LongRangeAisBroadcastMessage"
                    ext19 != null -> "ExtendedClassBPositionReport"
                    msgs.has("PositionReport") -> "PositionReport"
                    else -> "StandardClassBPositionReport"
                }
                val isExt19 = !isLongRange && inner === ext19
                if (typeKey !in msgTypes) {
                    capture?.dropCurrent("filter: message type $typeKey not subscribed")
                    return
                }

                if (inner.has("Valid") && !inner.isNull("Valid") && !inner.optBoolean("Valid")) {
                    capture?.dropCurrent("filter: Valid=false")
                    return
                }

                // One-shot field-name dump so we can verify the keys
                // aisstream actually sends.
                if (!statusKeyChecked) {
                    statusKeyChecked = true
                    LogBuffer.log("[$typeKey keys: ${inner.keys().asSequence().toList()}]")
                }

                val mmsi = meta.optLong("MMSI", 0L)
                if (mmsi == 0L) {
                    // No usable MMSI: don't let this create/overwrite a
                    // phantom "vessel 0" entry in the persistent store, which
                    // schedulePreload() would otherwise happily replay to
                    // OsmAnd as a real target after every restart.
                    capture?.dropCurrent("filter: missing/zero MMSI")
                    return
                }

                // Pre-conversion vessel filters (not possible in subscription).
                // Status filtering applies ONLY when the message actually
                // carries status 1 (At Anchor) or 5 (Moored) AND its speed is
                // consistent with that claim. Absent status = undeterminable:
                // the SOG fallback acts only then.
                val status = navStatus(inner)
                val sogRaw = if (inner.has("Sog") && !inner.isNull("Sog"))
                    inner.optDouble("Sog") else -1.0
                // AIS dead value: 102.3 (and anything above) means "not
                // available" — treat as unknown, never act on it.
                val sog = if (sogRaw >= 102.3) -1.0 else sogRaw
                val STATUS_SOG_MAX = 1.0   // kn above which anchor/moored status is stale

                if (skipAnchor && status == 1 && sog in 0.0..STATUS_SOG_MAX) {
                    capture?.dropCurrent("filter: skip_anchor (MMSI $mmsi)")
                    return   // At Anchor, slow
                }
                if (skipMoored && status == 5 && sog in 0.0..STATUS_SOG_MAX) {
                    capture?.dropCurrent("filter: skip_moored (MMSI $mmsi)")
                    return   // Moored, plausible
                }
                if (skipZeroSog && status == null && sog in 0.0..0.5) {
                    capture?.dropCurrent("filter: skip_zero_sog (MMSI $mmsi)")
                    return         // no status, stationary
                }

                // Optional (default OFF): blank a status that contradicts
                // observed motion, so the encoder emits 15 (not available)
                // instead of passing on "moored at 6 knots". Logged inline,
                // directly ahead of the NMEA sentence below.
                if (rewriteStale && (status == 1 || status == 5) && sog > STATUS_SOG_MAX) {
                    if (inner.has("NavigationalStatus")) inner.remove("NavigationalStatus")
                    if (inner.has("Status")) inner.remove("Status")
                    LogBuffer.log("[status rewritten to 15 — MMSI $mmsi claimed $status at $sog kn]")
                }

                if (!inner.has("Latitude") && meta.has("Latitude"))
                    inner.put("Latitude", meta.optDouble("Latitude"))
                if (!inner.has("Longitude") && meta.has("Longitude"))
                    inner.put("Longitude", meta.optDouble("Longitude"))
                if (!inner.has("UserID")) inner.put("UserID", mmsi)

                // Refresh the store's name + last-seen for this MMSI
                // (positions themselves are volatile, not cached), and tag
                // the vessel's class write-once from what it transmitted:
                // 1/2/3/27 => Class A, 18/19 => Class B.
                val name = AisEncoder.cleanName(meta.optString("ShipName", ""))
                if (isLongRange || typeKey == "PositionReport") store?.tagClass(mmsi, 'A')
                else store?.tagClass(mmsi, 'B')
                store?.updatePosition(
                    mmsi,
                    dblOrNull(inner, "Latitude"), dblOrNull(inner, "Longitude"),
                    sog, dblOrNull(inner, "Cog"), intOrNull(inner, "TrueHeading"),
                    status, name)

                // Type 19 carries static cargo (name/type/dims) in the
                // position report itself — learn it (Class B vessels have no
                // type 5; this may be their only static source). Teaches but
                // does NOT confirm: a real type 24 may still follow.
                if (isExt19) store?.absorbType19(mmsi, inner, name)

                // SYNTHETIC STATIC FIRST: emit cached static BEFORE the
                // position report so that even a decoder which resets the
                // whole vessel record on a static receipt ends up with the
                // REAL position as the last write. For merging decoders this
                // ordering is neutral. Class-aware: Class A vessels get a
                // synthetic type 5, Class B vessels get a synthetic type 24
                // A(+B). Rate-limited per vessel; suppressed once a real
                // static report (type 5 or 24) confirms this MMSI this
                // session.
                val synth = store?.syntheticStaticFor(mmsi)
                var sentSynth = false
                if (synth != null && AisEncoder.shouldSynthStatic(mmsi)) {
                    sentSynth = true
                    val lines = if (store?.vesselClass(mmsi) == 'B')
                        AisEncoder.encodeStaticBParts(synth)
                    else AisEncoder.encodeStaticA(synth)
                    for (s in lines) send(s)
                }

                // Names from MetaData (deduped, UNKNOWN-protected) — also
                // ahead of the position for the same last-write rationale.
                // Skipped when a synthetic static just went out: both a
                // type 5 and a type-24 pair already carry the name, so the
                // extra 24A would be a byte-identical duplicate.
                if (!sentSynth && AisEncoder.shouldSendName(mmsi, name)) {
                    send(AisEncoder.encodeStaticBName(mmsi, name))
                }

                // Real position report LAST — always the final word.
                send(when {
                    isLongRange -> AisEncoder.encodePosition27(inner)
                    isExt19 -> AisEncoder.encodePosition19(inner)
                    else -> AisEncoder.encodePosition(inner)
                })
                return
            }

            // ShipStaticData (type 5, Class A): learn from it, then relay the
            // ORIGINAL message verbatim — no merging of cached data into real
            // packets. Cache data is only for synthesis; anything the feed
            // actually sent (including Destination/ETA) passes through
            // untouched.
            val static = msgs.optJSONObject("ShipStaticData")
            if (static != null) {
                if ("ShipStaticData" !in msgTypes) {
                    capture?.dropCurrent("filter: ShipStaticData not subscribed")
                    return
                }
                val mmsi = if (static.has("UserID") && !static.isNull("UserID"))
                    static.optLong("UserID") else meta.optLong("MMSI", 0L)
                if (mmsi == 0L) {
                    capture?.dropCurrent("filter: missing/zero MMSI")
                    return
                }
                if (!static.has("UserID")) static.put("UserID", mmsi)

                // Update the cache + mark confirmed for this session.
                store?.absorbStatic(mmsi, static,
                    AisEncoder.cleanName(meta.optString("ShipName", "")))

                // Relay the original message. Only repair we permit: if the
                // feed omitted the Name entirely, fill it from the live meta
                // name so OsmAnd doesn't get a blank card. Never touches any
                // other field.
                val relay = JSONObject(static.toString())   // copy, don't mutate captured raw
                val metaName = AisEncoder.cleanName(meta.optString("ShipName", ""))
                if (relay.optString("Name").isBlank()) {
                    relay.put("Name", if (metaName != "UNKNOWN") metaName else "UNKNOWN")
                }

                for (s in AisEncoder.encodeStaticA(relay)) send(s)
                return
            }

            // StaticDataReport (type 24, Class B): the real static report for
            // Class B vessels. Learn from it (Part A: name; Part B: type /
            // callsign / dims), mark confirmed, and relay the applicable
            // part verbatim as a proper 24A/24B sentence.
            val sdr = msgs.optJSONObject("StaticDataReport")
            if (sdr != null) {
                if ("StaticDataReport" !in msgTypes) {
                    capture?.dropCurrent("filter: StaticDataReport not subscribed")
                    return
                }
                val mmsi = if (sdr.has("UserID") && !sdr.isNull("UserID"))
                    sdr.optLong("UserID") else meta.optLong("MMSI", 0L)
                if (mmsi == 0L) {
                    capture?.dropCurrent("filter: missing/zero MMSI")
                    return
                }
                if (!sdr.has("UserID")) sdr.put("UserID", mmsi)

                store?.absorbStaticReport(mmsi, sdr,
                    AisEncoder.cleanName(meta.optString("ShipName", "")))

                val line = AisEncoder.encodeStaticDataReport(sdr)
                if (line != null) send(line)
                else capture?.dropCurrent("static report part invalid")
                return
            }

            // Reached the end with a recognizable Message container but no
            // branch handled it: record it rather than letting it vanish.
            capture?.dropCurrent("unhandled MessageType: ${data.optString("MessageType", "?")}")
        } catch (e: Exception) {
            capture?.dropCurrent("error: ${e.message}")
            LogBuffer.log("[bad message skipped: ${e.message}]")
        }
    }

    private fun dblOrNull(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key) else null

    private fun intOrNull(o: JSONObject, key: String): Int? =
        if (o.has(key) && !o.isNull(key)) o.optInt(key) else null

    private fun send(sentence: String, mirror: Boolean = true) {
        if (mirror) {
            LogBuffer.log(sentence)               // mirrors to the app window (100-line cap)
            capture?.attachSentence(sentence)     // pairs output with its source frame
        }
        try {
            io.execute {
                try {
                    val data = (sentence + "\r\n").toByteArray(Charsets.US_ASCII)
                    val packet = DatagramPacket(data, data.size,
                        InetAddress.getByName("127.0.0.1"), UDP_PORT)
                    udp?.send(packet)
                } catch (_: Exception) {}
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor already torn down during shutdown — drop silently.
        }
    }

    override fun onDestroy() {
        running = false
        captureArmed = false
        stopCapture("service stopped")   // writes the file BEFORE timer teardown
        handler.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        try { locMgr?.removeUpdates(locationListener) } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { store?.save() } catch (_: Exception) {}   // final synchronous snapshot
        io.shutdown()
        super.onDestroy()
    }
}