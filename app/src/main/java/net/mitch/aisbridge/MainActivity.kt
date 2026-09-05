package net.mitch.aisbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: android.widget.Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(this)                      // AFTER setContentView

        btnToggle = findViewById(R.id.btnToggle)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        btnToggle.setOnClickListener {
            if (AisService.running) {
                stopService(Intent(this, AisService::class.java))
                btnToggle.text = "Start feed"
            } else {
                requestPermissionsAndStart()
            }
        }

        findViewById<android.widget.Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btnResend).setOnClickListener {
            AisEncoder.resetNameCache()
            LogBuffer.log("[name cache cleared — names will resend with next position reports]")
        }

        LogBuffer.listener = { line -> runOnUiThread { appendLog(line) } }
        logView.text = LogBuffer.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // Android 14+ refuses to start a location-type foreground service unless
    // the app holds a location permission — even in manual mode. So we always
    // request it before starting the feed.
    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startFeed()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startFeed()
        } else {
            LogBuffer.log("[permissions denied — cannot start]")
        }
    }

    private fun startFeed() {
        ContextCompat.startForegroundService(this, Intent(this, AisService::class.java))
        btnToggle.text = "Stop feed"
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
        val parts = logView.text.toString().split("\n")
        if (parts.size > 101) logView.text = parts.takeLast(101).joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        btnToggle.text = if (AisService.running) "Stop feed" else "Start feed"
    }

    override fun onDestroy() {
        LogBuffer.listener = null
        super.onDestroy()
    }
}

// Attach to any AppCompatActivity; pads the content view below system bars.
fun applyInsets(activity: AppCompatActivity) {
    val root = activity.findViewById<View>(android.R.id.content) ?: return
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    // Pull insets immediately — don't wait for the next dispatch pass.
    root.post {
        val inst = androidx.core.view.ViewCompat.getRootWindowInsets(root)
        if (inst != null) {
            val bars = inst.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        root.requestApplyInsets()
    }
}