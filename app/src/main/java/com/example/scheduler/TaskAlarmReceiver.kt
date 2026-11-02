package com.example.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.automation.AppManager
import com.example.data.AppDatabase
import com.example.data.RepeatMode
import com.example.data.TaskActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_EXECUTE_TASK) return

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Scheduled Task"
        val actionTypeName = intent.getStringExtra(EXTRA_TASK_ACTION) ?: TaskActionType.SPOKEN_REMINDER.name
        val targetParam = intent.getStringExtra(EXTRA_TASK_PARAM) ?: ""
        val repeatModeName = intent.getStringExtra(EXTRA_TASK_REPEAT) ?: RepeatMode.ONCE.name

        val actionType = try {
            TaskActionType.valueOf(actionTypeName)
        } catch (e: Exception) {
            TaskActionType.SPOKEN_REMINDER
        }
        val repeatMode = try {
            RepeatMode.valueOf(repeatModeName)
        } catch (e: Exception) {
            RepeatMode.ONCE
        }

        Log.d("TaskAlarmReceiver", "Executing alarm for task $taskId: $title ($actionTypeName)")

        // 1. Post notification
        showNotification(context, taskId, title, actionType, targetParam)

        // 2. Perform automated action if appropriate (e.g. launch app or execute action)
        val appManager = AppManager(context)
        if (actionType == TaskActionType.LAUNCH_APP && targetParam.isNotBlank()) {
            appManager.executeAction(actionType, targetParam)
        }

        // 3. Update database and reschedule if repeating
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val now = System.currentTimeMillis()
                val log = "Triggered successfully at " + java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(now)

                val isCompleted = repeatMode == RepeatMode.ONCE
                db.taskDao().markTaskExecuted(taskId, isCompleted, now, log)

                if (repeatMode != RepeatMode.ONCE) {
                    val nextTime = TaskScheduler.calculateNextTrigger(now, now, repeatMode)
                    val existing = db.taskDao().getTaskById(taskId)
                    if (existing != null) {
                        val updated = existing.copy(scheduledTimestamp = nextTime, isCompleted = false)
                        db.taskDao().updateTask(updated)
                        TaskScheduler(context).scheduleTask(updated)
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskAlarmReceiver", "Error updating DB on alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        taskId: Long,
        title: String,
        actionType: TaskActionType,
        targetParam: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = "omnivoice_scheduled_tasks"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Scheduled Activities & Routines",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts and triggers for advanced voice scheduled tasks"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NOTIFICATION_TASK_ID", taskId)
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val contentText = when (actionType) {
            TaskActionType.LAUNCH_APP -> "Launching application: $targetParam"
            TaskActionType.CALL_NUMBER -> "Scheduled call: $targetParam"
            TaskActionType.SEND_SMS -> "Scheduled message: $targetParam"
            TaskActionType.ROUTINE -> "Routine in progress: $targetParam"
            TaskActionType.OPEN_URL -> "Scheduled web search: $targetParam"
            else -> "Reminder: $title"
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("OmniVoice: $title")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setContentIntent(mainPendingIntent)
            .build()

        notificationManager.notify(taskId.toInt(), notification)
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.example.omnivoice.EXECUTE_TASK"
        const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
        const val EXTRA_TASK_TITLE = "EXTRA_TASK_TITLE"
        const val EXTRA_TASK_ACTION = "EXTRA_TASK_ACTION"
        const val EXTRA_TASK_PARAM = "EXTRA_TASK_PARAM"
        const val EXTRA_TASK_REPEAT = "EXTRA_TASK_REPEAT"
    }
}
