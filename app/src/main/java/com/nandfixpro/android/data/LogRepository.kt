package com.nandfixpro.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    USB("USB"),
    WARN("WARN"),
    ERROR("ERREUR"),
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    val clock: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))

    val formatted: String
        get() = "$clock [${level.label}] $tag — $message"
}

/**
 * Central diagnostic journal. Every USB, analysis and backup operation is written
 * here so users can copy evidence without a debugger. Values from prod.keys and
 * PRODINFO secrets are never logged by callers.
 */
class LogRepository {

    private val lock = Any()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun debug(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)
    fun usb(tag: String, message: String) = append(LogLevel.USB, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun append(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message.replace('\n', ' '),
        )
        synchronized(lock) {
            val updated = _entries.value + entry
            _entries.value = if (updated.size > MAX_ENTRIES) updated.takeLast(MAX_ENTRIES) else updated
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    fun exportText(): String = buildString {
        appendLine("NAND Fix Pro Android — journal d'opérations")
        appendLine("Exporté le ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("=".repeat(64))
        _entries.value.forEach { appendLine(it.formatted) }
    }

    companion object {
        private const val MAX_ENTRIES = 2000
    }
}
