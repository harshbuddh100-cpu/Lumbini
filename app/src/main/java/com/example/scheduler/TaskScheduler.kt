package com.example.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.RepeatMode
import com.example.data.TaskEntity
import java.util.Calendar

class TaskScheduler(private val context: Context) {

    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleTask(task: TaskEntity) {
        if (!task.isEnabled) {
            cancelTask(task.id)
            return
        }

        var triggerTime = task.scheduledTimestamp
        val now = System.currentTimeMillis()

        // If trigger time is in the past, calculate next instance if repeating
        if (triggerTime <= now) {
            if (task.repeatMode != RepeatMode.ONCE) {
                triggerTime = calculateNextTrigger(now, task.scheduledTimestamp, task.repeatMode)
            } else {
                Log.w("TaskScheduler", "One-time task ${task.id} timestamp is in the past")
                return
            }
        }

        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_EXECUTE_TASK
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, task.id)
            putExtra(TaskAlarmReceiver.EXTRA_TASK_TITLE, task.title)
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ACTION, task.actionType.name)
            putExtra(TaskAlarmReceiver.EXTRA_TASK_PARAM, task.targetParam)
            putExtra(TaskAlarmReceiver.EXTRA_TASK_REPEAT, task.repeatMode.name)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
            Log.d("TaskScheduler", "Successfully scheduled task ${task.id} for $triggerTime")
        } catch (e: SecurityException) {
            Log.e("TaskScheduler", "Permission denied for exact alarm", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } catch (e: Exception) {
            Log.e("TaskScheduler", "Failed to schedule alarm for task ${task.id}", e)
        }
    }

    fun cancelTask(taskId: Long) {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_EXECUTE_TASK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("TaskScheduler", "Cancelled alarm for task $taskId")
        }
    }

    companion object {
        fun calculateNextTrigger(now: Long, targetTime: Long, repeatMode: RepeatMode): Long {
            val targetCal = Calendar.getInstance().apply { timeInMillis = targetTime }
            val nowCal = Calendar.getInstance().apply { timeInMillis = now }

            // Align today's time with scheduled hour and minute
            targetCal.set(Calendar.YEAR, nowCal.get(Calendar.YEAR))
            targetCal.set(Calendar.MONTH, nowCal.get(Calendar.MONTH))
            targetCal.set(Calendar.DAY_OF_MONTH, nowCal.get(Calendar.DAY_OF_MONTH))

            // If time already passed today, advance day
            if (targetCal.timeInMillis <= now) {
                targetCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            when (repeatMode) {
                RepeatMode.ONCE -> { /* Already computed */ }
                RepeatMode.DAILY -> { /* targetCal is correct */ }
                RepeatMode.WEEKDAYS -> {
                    while (targetCal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                        targetCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                        targetCal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                RepeatMode.WEEKLY -> {
                    val originalCal = Calendar.getInstance().apply { timeInMillis = targetTime }
                    val targetDayOfWeek = originalCal.get(Calendar.DAY_OF_WEEK)
                    while (targetCal.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek) {
                        targetCal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
            }
            return targetCal.timeInMillis
        }
    }
}
