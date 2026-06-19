package com.cahdz.alexa.tools

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cahdz.alexa.AlexaApp
import com.cahdz.alexa.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

class AlarmTools(private val context: Context) {

    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun setAlarm(time: String, label: String = "Alarma"): String {
        return try {
            val triggerTime = parseTime(time)
            val entry = AlarmEntry(
                id = UUID.randomUUID().toString().take(8),
                time = triggerTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                label = label,
                type = "alarm",
            )
            saveAlarm(entry)
            scheduleSystemAlarm(entry)
            "Alarma '$label' programada para ${triggerTime.format(DateTimeFormatter.ofPattern("HH:mm"))} (id: ${entry.id})"
        } catch (e: Exception) {
            "Error al crear alarma: ${e.message}"
        }
    }

    fun setReminder(time: String, message: String): String {
        return try {
            val triggerTime = parseTime(time)
            val entry = AlarmEntry(
                id = UUID.randomUUID().toString().take(8),
                time = triggerTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                label = message,
                type = "reminder",
            )
            saveAlarm(entry)
            scheduleSystemAlarm(entry)
            "Recordatorio '$message' programado para ${triggerTime.format(DateTimeFormatter.ofPattern("HH:mm"))} (id: ${entry.id})"
        } catch (e: Exception) {
            "Error al crear recordatorio: ${e.message}"
        }
    }

    fun listAlarms(): String {
        val alarms = getAlarms().filter {
            LocalDateTime.parse(it.time) > LocalDateTime.now()
        }
        if (alarms.isEmpty()) return "No hay alarmas ni recordatorios activos"
        return alarms.joinToString("\n") { a ->
            val dt = LocalDateTime.parse(a.time)
            "- [${a.id}] ${a.type}: '${a.label}' a las ${dt.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        }
    }

    fun cancelAlarm(alarmId: String): String {
        val alarms = getAlarms().toMutableList()
        val idx = alarms.indexOfFirst { it.id == alarmId || it.label.contains(alarmId, ignoreCase = true) }
        if (idx >= 0) {
            val removed = alarms.removeAt(idx)
            cancelSystemAlarm(removed)
            prefs.edit().putString("list", gson.toJson(alarms)).apply()
            return "Alarma/recordatorio cancelado"
        }
        return "No encontré alarma con id o nombre '$alarmId'"
    }

    fun stopAlarm(): String {
        // Cancel any ringing notification
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ALARM_NOTIFICATION_ID)
        return "Alarma apagada"
    }

    private fun parseTime(timeStr: String): LocalDateTime {
        val now = LocalDateTime.now()
        val lower = timeStr.trim().lowercase()

        // Relative: "5 minutos", "1 hora", "30 segundos"
        val relativeUnits = mapOf("segundo" to 1L, "minuto" to 60L, "hora" to 3600L)
        for ((unit, seconds) in relativeUnits) {
            if (lower.contains(unit)) {
                val digits = lower.filter { it.isDigit() }
                if (digits.isNotEmpty()) {
                    return now.plusSeconds(digits.toLong() * seconds)
                }
            }
        }

        // Absolute: "7:30", "19:00", "14:30"
        val timeFormats = listOf("H:mm", "HH:mm", "h:mm a")
        for (fmt in timeFormats) {
            try {
                val parsed = LocalTime.parse(lower, DateTimeFormatter.ofPattern(fmt))
                var result = now.with(parsed)
                if (result.isBefore(now)) result = result.plusDays(1)
                return result
            } catch (_: DateTimeParseException) {}
        }

        // Fallback: digits as minutes
        val digits = lower.filter { it.isDigit() }
        if (digits.isNotEmpty()) {
            return now.plusMinutes(digits.toLong())
        }

        throw IllegalArgumentException("No pude entender el tiempo: $timeStr")
    }

    private fun scheduleSystemAlarm(entry: AlarmEntry) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAt = LocalDateTime.parse(entry.time)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("id", entry.id)
            putExtra("label", entry.label)
            putExtra("type", entry.type)
        }
        val pending = PendingIntent.getBroadcast(
            context, entry.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun cancelSystemAlarm(entry: AlarmEntry) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, entry.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
    }

    private fun saveAlarm(entry: AlarmEntry) {
        val alarms = getAlarms().toMutableList()
        alarms.add(entry)
        prefs.edit().putString("list", gson.toJson(alarms)).apply()
    }

    private fun getAlarms(): List<AlarmEntry> {
        val json = prefs.getString("list", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<AlarmEntry>>() {}.type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class AlarmEntry(
        val id: String,
        val time: String,
        val label: String,
        val type: String,
    )

    companion object {
        const val ALARM_NOTIFICATION_ID = 1000
    }
}

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Alarma"
        val type = intent.getStringExtra("type") ?: "alarm"

        Log.i("AlarmReceiver", "Triggered $type: $label")

        val notification = NotificationCompat.Builder(context, AlexaApp.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (type == "reminder") "Recordatorio" else "Alarma")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(AlarmTools.ALARM_NOTIFICATION_ID, notification)

        // Vibrate
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))

        // Play alarm sound
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to play alarm", e)
        }
    }
}
