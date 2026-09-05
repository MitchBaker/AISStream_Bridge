package net.mitch.aisbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import androidx.core.content.FileProvider

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyField: EditText
    private lateinit var spanField: EditText
    private lateinit var keyStatus: TextView
    private lateinit var gpsRadio: android.widget.RadioButton
    private lateinit var manualRadio: android.widget.RadioButton
    private lateinit var manualLatField: EditText
    private lateinit var manualLonField: EditText
    private lateinit var chkPosA: android.widget.CheckBox
    private lateinit var chkPosB: android.widget.CheckBox
    private lateinit var chkPosBe: android.widget.CheckBox
    private lateinit var chkLongRange: android.widget.CheckBox
    private lateinit var chkStaticData: android.widget.CheckBox
    private lateinit var chkStaticDataRpt: android.widget.CheckBox
    private lateinit var swSkipAnchored: Switch
    private lateinit var swSkipMoored: Switch
    private lateinit var swSkipZeroSog: Switch
    private lateinit var swRewriteStale: Switch
    private lateinit var buildStamp: TextView
    private lateinit var btnExport: Button

    // SAF picker for restoring a vessel snapshot. Must be registered before
    // onCreate completes — field initializer satisfies that. Result is the
    // content URI of the picked file, or null if the user backed out.
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVessels(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyInsets(this)

        apiKeyField = findViewById(R.id.apiKey)
        spanField = findViewById(R.id.spanNm)
        keyStatus = findViewById(R.id.keyStatus)
        gpsRadio = findViewById(R.id.srcGps)
        manualRadio = findViewById(R.id.sourceManual)
        manualLatField = findViewById(R.id.manualLat)
        manualLonField = findViewById(R.id.manualLon)
        chkPosA = findViewById(R.id.chkPosA)
        chkPosB = findViewById(R.id.chkPosB)
        chkPosBe = findViewById(R.id.chkPosBE)
        chkLongRange = findViewById(R.id.chkLongRange)
        chkStaticData = findViewById(R.id.chkStaticData)
        chkStaticDataRpt = findViewById(R.id.chkStaticDataRpt)
        swSkipAnchored = findViewById(R.id.swSkipAnchored)
        swSkipMoored = findViewById(R.id.swSkipMoored)
        swSkipZeroSog = findViewById(R.id.swSkipZeroSog)
        swRewriteStale = findViewById(R.id.swRewriteStale)

        val prefs = getSharedPreferences("aisbridge", MODE_PRIVATE)
        keyStatus.text = if (!prefs.getString("api_key", "").isNullOrBlank())
            "API key: set" else "API key: not set"
        spanField.setText(prefs.getString("span_nm", "40"))
        manualLatField.setText(prefs.getString("manual_lat", ""))
        manualLonField.setText(prefs.getString("manual_lon", ""))
        if (prefs.getString("source", "gps") == "manual") manualRadio.isChecked = true
        else gpsRadio.isChecked = true

        val defaultTypes = setOf("PositionReport", "StandardClassBPositionReport",
            "ExtendedClassBPositionReport", "LongRangeAisBroadcastMessage",
            "ShipStaticData", "StaticDataReport")
        val types = prefs.getStringSet("msg_types", defaultTypes) ?: defaultTypes
        chkPosA.isChecked = types.contains("PositionReport")
        chkPosB.isChecked = types.contains("StandardClassBPositionReport")
        chkPosBe.isChecked = types.contains("ExtendedClassBPositionReport")
        chkLongRange.isChecked = types.contains("LongRangeAisBroadcastMessage")
        chkStaticData.isChecked = types.contains("ShipStaticData")
        chkStaticDataRpt.isChecked = types.contains("StaticDataReport")

        swSkipAnchored.isChecked = prefs.getBoolean("skip_anchor", false)
        swSkipMoored.isChecked = prefs.getBoolean("skip_moored", false)
        swSkipZeroSog.isChecked = prefs.getBoolean("skip_zero_sog", false)
        swRewriteStale.isChecked = prefs.getBoolean("rewrite_stale_status", false)

        gpsRadio.setOnCheckedChangeListener { _, _ -> updateManualVisibility() }
        manualRadio.setOnCheckedChangeListener { _, _ -> updateManualVisibility() }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val edit = prefs.edit()

            val entered = apiKeyField.text.toString().trim()
            if (entered.isNotEmpty()) edit.putString("api_key", entered)

            val spanNm = spanField.text.toString().toDoubleOrNull()
            if (spanNm != null && spanNm > 0) edit.putString("span_nm", spanNm.toString())

            edit.putString("source", if (manualRadio.isChecked) "manual" else "gps")
            val mLat = manualLatField.text.toString().toDoubleOrNull()
            val mLon = manualLonField.text.toString().toDoubleOrNull()
            if (mLat != null) edit.putString("manual_lat", mLat.toString())
            if (mLon != null) edit.putString("manual_lon", mLon.toString())

            val sel = mutableSetOf<String>()
            if (chkPosA.isChecked) sel.add("PositionReport")
            if (chkPosB.isChecked) sel.add("StandardClassBPositionReport")
            if (chkPosBe.isChecked) sel.add("ExtendedClassBPositionReport")
            if (chkLongRange.isChecked) sel.add("LongRangeAisBroadcastMessage")
            if (chkStaticData.isChecked) sel.add("ShipStaticData")
            if (chkStaticDataRpt.isChecked) sel.add("StaticDataReport")
            edit.putStringSet("msg_types", sel.ifEmpty { defaultTypes })

            edit.putBoolean("skip_anchor", swSkipAnchored.isChecked)
            edit.putBoolean("skip_moored", swSkipMoored.isChecked)
            edit.putBoolean("skip_zero_sog", swSkipZeroSog.isChecked)
            edit.putBoolean("rewrite_stale_status", swRewriteStale.isChecked)

            edit.apply()

            apiKeyField.setText("")             // key is never displayed again
            keyStatus.text = if (prefs.getString("api_key", "").isNullOrBlank())
                "API key: not set" else "API key: set"

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

            // Live-apply: tell a running feed to re-read its settings now.
            // Delivered via startService() — a broadcast never reaches a
            // Service's onStartCommand.
            startService(Intent(this, AisService::class.java)
                .setAction("net.mitch.aisbridge.RELOAD"))
        }
        buildStamp = findViewById(R.id.buildStamp)
        try {
            val v = packageManager.getPackageInfo(packageName, 0)
            buildStamp.text = "AIS Bridge v${v.versionName}"
        } catch (_: Exception) {}
        btnExport = findViewById(R.id.btnExport)
        btnExport.setOnClickListener { exportVessels() }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importPicker.launch(arrayOf("application/json"))
        }
        findViewById<Button>(R.id.btnClearStore).setOnClickListener { confirmClearStore() }

        val captureMinutes = findViewById<EditText>(R.id.captureMinutes)
        val btnCapture = findViewById<Button>(R.id.btnCapture)
        val btnShareDebug = findViewById<Button>(R.id.btnShareDebug)

        btnCapture.setOnClickListener {
            if (DebugCapture.isActive) {
                startService(Intent(this, AisService::class.java)
                    .setAction(AisService.ACTION_STOP_CAPTURE))
                btnCapture.text = "Start debug capture"
            } else {
                val mins = captureMinutes.text.toString().toIntOrNull()?.coerceIn(1, 120) ?: 10
                startService(Intent(this, AisService::class.java)
                    .setAction(AisService.ACTION_START_CAPTURE).putExtra("minutes", mins))
                btnCapture.text = "Stop debug capture"
            }
        }

        btnShareDebug.setOnClickListener {
            val dir = File(cacheDir, "exports")
            val newest = dir.listFiles { f -> f.name.startsWith("debug-") }?.maxByOrNull { it.name }
            if (newest == null) {
                Toast.makeText(this, "No debug capture files", Toast.LENGTH_SHORT).show()
            } else {
                val uri = FileProvider.getUriForFile(this, "net.mitch.aisbridge.fileprovider", newest)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share debug capture"))
            }
        }
    }

    // Copy the persisted vessel store to a shareable, timestamped file and
    // hand it to Android's share sheet. Works whether or not the feed is
    // currently running (reads the last saved snapshot).
    private fun exportVessels() {
        val src = File(filesDir, "vessels.json")
        if (!src.exists()) {
            Toast.makeText(this, "No vessel data yet — run the feed first",
                Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(java.util.Date())
            val dst = File(dir, "vessels-$stamp.json")
            src.copyTo(dst, overwrite = true)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "net.mitch.aisbridge.fileprovider", dst)

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share vessel data"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Restore a previously exported snapshot. The picked file is copied into
    // the app's own cache dir first, then handed to the service BY PATH —
    // the content itself never rides an Intent extra, so the 1 MB binder
    // transaction limit can't bite no matter how big the snapshot is.
    private fun importVessels(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Selected file is empty", Toast.LENGTH_SHORT).show()
                return
            }
            // Cheap sanity check before involving the service: it must be a
            // JSON array (our snapshot format), not an arbitrary file.
            val first = text.trim().firstOrNull()
            if (first != '[') {
                Toast.makeText(this, "Not a vessel snapshot (expected a JSON array)",
                    Toast.LENGTH_LONG).show()
                return
            }
            val tmp = File(cacheDir, "vessels-import.json")
            tmp.writeText(text)
            startService(Intent(this, AisService::class.java)
                .setAction(AisService.ACTION_IMPORT)
                .putExtra("path", tmp.absolutePath))
            Toast.makeText(this, "Importing vessels...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Delete the vessel store: confirm first, showing what's actually in the
    // last persisted snapshot so the user knows what they're about to lose.
    private fun confirmClearStore() {
        val f = File(filesDir, "vessels.json")
        if (!f.exists()) {
            Toast.makeText(this, "Vessel store is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        val n = try {
            org.json.JSONArray(f.readText()).length()
        } catch (_: Exception) { -1 }
        val msg = if (n >= 0)
            "Permanently delete all $n stored vessel identities?\n\n" +
                    "Names, callsigns, IMO numbers, dimensions and draught history " +
                    "will be wiped. Positions are not affected; the store rebuilds " +
                    "as vessels report in."
        else
            "Permanently delete the stored vessel identities? The store " +
                    "rebuilds as vessels report in."
        AlertDialog.Builder(this)
            .setTitle("Delete vessel store")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                startService(Intent(this, AisService::class.java)
                    .setAction(AisService.ACTION_CLEAR_STORE))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateManualVisibility() {
        val show = manualRadio.isChecked
        manualLatField.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        manualLonField.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }
}