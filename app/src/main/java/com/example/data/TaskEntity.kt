package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskActionType {
    LAUNCH_APP,
    SPOKEN_REMINDER,
    SYSTEM_ALARM,
    OPEN_URL,
    CALL_NUMBER,
    SEND_SMS,
    SYSTEM_SETTING,
    ROUTINE
}

enum class RepeatMode {
    ONCE,
    DAILY,
    WEEKDAYS,
    WEEKLY
}

@Entity(tableName = "scheduled_tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val actionType: TaskActionType,
    val targetParam: String = "", // e.g. package name, query, phone number, url, or setting key
    val scheduledTimestamp: Long, // epoch millis
    val repeatMode: RepeatMode = RepeatMode.ONCE,
    val isEnabled: Boolean = true,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecutedAt: Long? = null,
    val executionLog: String = ""
)

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val category: String = "App"
)

data class VoiceCommandHistory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userPrompt: String,
    val aiResponse: String,
    val actionType: TaskActionType?,
    val targetParam: String?,
    val executionStatus: String,
    val timestamp: Long = System.currentTimeMillis()
)
