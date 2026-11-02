package com.example.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device rebooted - rescheduling all active tasks")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val tasks = db.taskDao().getAllEnabledTasksSync()
                    val scheduler = TaskScheduler(context)
                    for (task in tasks) {
                        scheduler.scheduleTask(task)
                    }
                    Log.d("BootReceiver", "Rescheduled ${tasks.size} tasks after boot")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to reschedule tasks after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
