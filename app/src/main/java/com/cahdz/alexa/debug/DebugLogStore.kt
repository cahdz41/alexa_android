package com.cahdz.alexa.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogStore {
    private const val MAX_ENTRIES = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        add("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(tag, message)
        } else {
            Log.w(tag, message, throwable)
        }
        add("W", tag, format(message, throwable))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
        add("E", tag, format(message, throwable))
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun textSnapshot(): String {
        return _entries.value.joinToString(separator = "\n")
    }

    private fun add(level: String, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"
        _entries.value = (_entries.value + line).takeLast(MAX_ENTRIES)
    }

    private fun format(message: String, throwable: Throwable?): String {
        if (throwable == null) return message
        val error = throwable.message ?: throwable.javaClass.simpleName
        return "$message - $error"
    }
}
