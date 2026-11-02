package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.example.data.InstalledApp
import com.example.data.TaskActionType

class AppManager(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    /**
     * Retrieves all launchable applications installed on the Android device.
     */
    fun getInstalledApps(): List<InstalledApp> {
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList = packageManager.queryIntentActivities(launchIntent, 0)
        
        val apps = mutableListOf<InstalledApp>()
        for (info in resolveInfoList) {
            val pkg = info.activityInfo.packageName
            // Skip our own app package
            if (pkg == context.packageName) continue

            val appName = info.loadLabel(packageManager).toString()
            val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val category = when {
                pkg.contains("camera", ignoreCase = true) -> "Media"
                pkg.contains("music", ignoreCase = true) || pkg.contains("audio", ignoreCase = true) || pkg.contains("spotify", ignoreCase = true) -> "Audio"
                pkg.contains("browser", ignoreCase = true) || pkg.contains("chrome", ignoreCase = true) -> "Web"
                pkg.contains("clock", ignoreCase = true) || pkg.contains("alarm", ignoreCase = true) -> "Utility"
                pkg.contains("calc", ignoreCase = true) -> "Tools"
                pkg.contains("setting", ignoreCase = true) -> "System"
                pkg.contains("map", ignoreCase = true) -> "Navigation"
                pkg.contains("mail", ignoreCase = true) || pkg.contains("message", ignoreCase = true) -> "Communication"
                else -> if (isSystem) "System" else "Productivity"
            }

            apps.add(InstalledApp(packageName = pkg, appName = appName, isSystemApp = isSystem, category = category))
        }

        return apps.distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
    }

    /**
     * Finds the best matching installed app given a voice query (e.g. "youtube", "camera", "calc").
     */
    fun findAppByName(nameQuery: String): InstalledApp? {
        val cleanQuery = nameQuery.trim().lowercase()
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("start ")
            .trim()

        val allApps = getInstalledApps()

        // 1. Exact match on app name
        allApps.firstOrNull { it.appName.equals(cleanQuery, ignoreCase = true) }?.let { return it }

        // 2. Starts with app name
        allApps.firstOrNull { it.appName.lowercase().startsWith(cleanQuery) }?.let { return it }

        // 3. Contains name
        allApps.firstOrNull { it.appName.lowercase().contains(cleanQuery) }?.let { return it }

        // 4. Package name contains
        allApps.firstOrNull { it.packageName.lowercase().contains(cleanQuery) }?.let { return it }

        // 5. Common aliases
        val alias = when (cleanQuery) {
            "browser", "internet", "web" -> "chrome"
            "calc" -> "calculator"
            "insta" -> "instagram"
            "photos", "pics" -> "gallery"
            "timer" -> "clock"
            "mail" -> "gmail"
            "nav", "directions" -> "maps"
            else -> null
        }
        if (alias != null) {
            allApps.firstOrNull { it.appName.lowercase().contains(alias) || it.packageName.lowercase().contains(alias) }?.let { return it }
        }

        return null
    }

    /**
     * Launches an app by its package name.
     */
    fun launchAppByPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                Log.w("AppManager", "No launch intent found for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e("AppManager", "Error launching $packageName", e)
            false
        }
    }

    /**
     * Executes deep system action based on action type and target parameter.
     */
    fun executeAction(actionType: TaskActionType, targetParam: String): Pair<Boolean, String> {
        return try {
            when (actionType) {
                TaskActionType.LAUNCH_APP -> {
                    // targetParam can be package name or app label
                    val pkg = if (targetParam.contains(".")) targetParam else findAppByName(targetParam)?.packageName
                    if (pkg != null && launchAppByPackage(pkg)) {
                        Pair(true, "Launched app: $targetParam")
                    } else {
                        Pair(false, "Could not find or launch app: $targetParam")
                    }
                }
                TaskActionType.CALL_NUMBER -> {
                    val cleanNumber = targetParam.filter { it.isDigit() || it == '+' }
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$cleanNumber")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Pair(true, "Dialing $cleanNumber")
                }
                TaskActionType.SEND_SMS -> {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$targetParam")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Pair(true, "Opened messaging for $targetParam")
                }
                TaskActionType.OPEN_URL -> {
                    val url = if (targetParam.startsWith("http://") || targetParam.startsWith("https://")) {
                        targetParam
                    } else {
                        "https://www.google.com/search?q=" + Uri.encode(targetParam)
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Pair(true, "Opened search: $targetParam")
                }
                TaskActionType.SYSTEM_ALARM -> {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Pair(true, "Opened Clock/Alarms")
                }
                TaskActionType.SYSTEM_SETTING -> {
                    val intent = when (targetParam.lowercase()) {
                        "wifi", "wi-fi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth", "bt" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        "sound", "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                        "display", "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                        "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        "date", "time" -> Intent(Settings.ACTION_DATE_SETTINGS)
                        else -> Intent(Settings.ACTION_SETTINGS)
                    }.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Pair(true, "Opened system settings: $targetParam")
                }
                TaskActionType.SPOKEN_REMINDER -> {
                    Pair(true, "Reminder noted: $targetParam")
                }
                TaskActionType.ROUTINE -> {
                    Pair(true, "Triggered routine: $targetParam")
                }
            }
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to execute action $actionType ($targetParam)", e)
            Pair(false, "Failed to execute: ${e.localizedMessage}")
        }
    }

    fun openCamera(): Boolean {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppManager", "Cannot open camera", e)
            false
        }
    }

    fun openMaps(query: String): Boolean {
        return try {
            val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mapIntent.resolveActivity(packageManager) != null) {
                context.startActivity(mapIntent)
            } else {
                val genericIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
            }
            true
        } catch (e: Exception) {
            Log.e("AppManager", "Cannot open maps", e)
            false
        }
    }
}
