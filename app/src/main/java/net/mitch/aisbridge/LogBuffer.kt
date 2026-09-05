package net.mitch.aisbridge

import android.os.Handler
import android.os.Looper

object LogBuffer {
    private const val MAX_LINES = 100
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(line: String) {
        synchronized(this) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        val cb = listener
        if (cb != null) main.post { cb(line) }
    }

    fun snapshot(): List<String> = synchronized(this) { lines.toList() }
}