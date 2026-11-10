package com.example.autoposter

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

/** Short in-memory event log (the queue itself is persistent in PostStore). */
object PostLog {
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    fun add(s: String) { main.post { lines.add(s); if (lines.size > 150) lines.removeAt(0) } }
    fun clear() { main.post { lines.clear() } }
}
