package com.example.voice

import android.util.Log
import com.example.BuildConfig
import com.example.data.RepeatMode
import com.example.data.TaskActionType
import com.example.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ParsedCommand(
    val actionType: TaskActionType,
    val targetParam: String,
    val spokenResponse: String,
    val isScheduled: Boolean = false,
    val scheduledTimestamp: Long? = null,
    val repeatMode: RepeatMode = RepeatMode.ONCE,
    val taskTitle: String = ""
)

class GeminiAssistantEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun parseAndProcess(userPrompt: String): ParsedCommand = withContext(Dispatchers.IO) {
        val trimmed = userPrompt.trim()

        // 1. Try local rule-based intent parsing (ultra-fast, works 100% offline)
        val localResult = parseLocally(trimmed)

        // 2. If Gemini API key is configured and user is asking a complex or conversational question, query Gemini
        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasValidKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        if (hasValidKey && (localResult.actionType == TaskActionType.SPOKEN_REMINDER && !localResult.isScheduled)) {
            try {
                val geminiResponse = callGeminiApi(trimmed, apiKey)
                if (geminiResponse.isNotBlank()) {
                    return@withContext localResult.copy(spokenResponse = geminiResponse)
                }
            } catch (e: Exception) {
                Log.w("GeminiEngine", "Gemini API call failed, using local response", e)
            }
        }

        return@withContext localResult
    }

    private fun parseLocally(prompt: String): ParsedCommand {
        val lower = prompt.lowercase()

        // --- 1. SCHEDULE / REMIND COMMANDS ---
        if (lower.startsWith("schedule ") || lower.startsWith("remind me to ") || lower.contains(" in advance") ||
            lower.contains("remind me ") || (lower.contains("schedule") && (lower.contains(" at ") || lower.contains(" in ")))) {
            
            val repeatMode = when {
                lower.contains("every day") || lower.contains("daily") -> RepeatMode.DAILY
                lower.contains("weekday") || lower.contains("every weekday") -> RepeatMode.WEEKDAYS
                lower.contains("every week") || lower.contains("weekly") -> RepeatMode.WEEKLY
                else -> RepeatMode.ONCE
            }

            val (timeMillis, taskTitle) = extractScheduleTimeAndTitle(prompt)
            val cleanTitle = taskTitle.ifBlank { "Scheduled Activity" }
            val formattedTime = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(timeMillis)

            // Check if scheduled activity is an app launch
            val appMatch = if (cleanTitle.lowercase().startsWith("open ") || cleanTitle.lowercase().startsWith("launch ")) {
                cleanTitle.substringAfter(" ").trim()
            } else ""

            val actionType = if (appMatch.isNotBlank()) TaskActionType.LAUNCH_APP else TaskActionType.SPOKEN_REMINDER
            val param = if (appMatch.isNotBlank()) appMatch else cleanTitle

            val repeatLabel = if (repeatMode != RepeatMode.ONCE) " (${repeatMode.name.lowercase()})" else ""
            val spoken = "I've scheduled '$cleanTitle' for $formattedTime$repeatLabel."

            return ParsedCommand(
                actionType = actionType,
                targetParam = param,
                spokenResponse = spoken,
                isScheduled = true,
                scheduledTimestamp = timeMillis,
                repeatMode = repeatMode,
                taskTitle = cleanTitle
            )
        }

        // --- 2. LAUNCH / OPEN APP ---
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start app ") || lower.startsWith("run ")) {
            val appName = lower
                .removePrefix("open ")
                .removePrefix("launch ")
                .removePrefix("start app ")
                .removePrefix("run ")
                .removeSuffix(" app")
                .removeSuffix(" please")
                .trim()

            return ParsedCommand(
                actionType = TaskActionType.LAUNCH_APP,
                targetParam = appName,
                spokenResponse = "Opening $appName."
            )
        }

        // --- 3. SYSTEM SETTINGS ---
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            return ParsedCommand(TaskActionType.SYSTEM_SETTING, "wifi", "Opening Wi-Fi settings.")
        }
        if (lower.contains("bluetooth")) {
            return ParsedCommand(TaskActionType.SYSTEM_SETTING, "bluetooth", "Opening Bluetooth settings.")
        }
        if (lower.contains("volume") || lower.contains("sound setting")) {
            return ParsedCommand(TaskActionType.SYSTEM_SETTING, "sound", "Opening Sound settings.")
        }
        if (lower.contains("brightness") || lower.contains("display setting")) {
            return ParsedCommand(TaskActionType.SYSTEM_SETTING, "display", "Opening Display settings.")
        }
        if (lower.contains("setting") || lower.contains("settings")) {
            return ParsedCommand(TaskActionType.SYSTEM_SETTING, "settings", "Opening Android Settings.")
        }

        // --- 4. PHONE CALL ---
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val target = prompt.substringAfter(" ").trim()
            return ParsedCommand(
                actionType = TaskActionType.CALL_NUMBER,
                targetParam = target,
                spokenResponse = "Calling $target."
            )
        }

        // --- 5. SMS / MESSAGE ---
        if (lower.startsWith("send message to ") || lower.startsWith("text ") || lower.startsWith("send sms to ")) {
            val clean = prompt
                .removePrefix("send message to ")
                .removePrefix("text ")
                .removePrefix("send sms to ")
                .trim()
            val target = clean.substringBefore(" saying").substringBefore(" that").trim()
            return ParsedCommand(
                actionType = TaskActionType.SEND_SMS,
                targetParam = target,
                spokenResponse = "Opening messages for $target."
            )
        }

        // --- 6. ALARM / TIMER ---
        if (lower.startsWith("set timer") || lower.startsWith("set alarm") || lower.contains("wake me up")) {
            return ParsedCommand(
                actionType = TaskActionType.SYSTEM_ALARM,
                targetParam = prompt,
                spokenResponse = "Opening Alarm & Timer clock."
            )
        }

        // --- 7. ROUTINES ---
        if (lower.contains("morning routine") || lower.contains("wake up routine")) {
            return ParsedCommand(
                actionType = TaskActionType.ROUTINE,
                targetParam = "morning",
                spokenResponse = "Starting your Morning Briefing routine."
            )
        }
        if (lower.contains("focus mode") || lower.contains("work routine")) {
            return ParsedCommand(
                actionType = TaskActionType.ROUTINE,
                targetParam = "focus",
                spokenResponse = "Initiating Focus Mode automation."
            )
        }
        if (lower.contains("workout routine") || lower.contains("fitness routine")) {
            return ParsedCommand(
                actionType = TaskActionType.ROUTINE,
                targetParam = "workout",
                spokenResponse = "Kicking off your Workout Routine."
            )
        }

        // --- 8. WEB SEARCH ---
        if (lower.startsWith("search ") || lower.startsWith("google ") || lower.startsWith("look up ") || lower.startsWith("find ")) {
            val query = prompt
                .removePrefix("search for ")
                .removePrefix("search ")
                .removePrefix("google ")
                .removePrefix("look up ")
                .removePrefix("find ")
                .trim()
            return ParsedCommand(
                actionType = TaskActionType.OPEN_URL,
                targetParam = query,
                spokenResponse = "Searching the web for $query."
            )
        }

        // --- 9. DEFAULT / CONVERSATIONAL ---
        val response = generateConversationalResponse(prompt)
        return ParsedCommand(
            actionType = TaskActionType.SPOKEN_REMINDER,
            targetParam = prompt,
            spokenResponse = response
        )
    }

    private fun extractScheduleTimeAndTitle(prompt: String): Pair<Long, String> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        val lower = prompt.lowercase()

        // 1. "in X minutes" or "in X hours"
        val inMinutesMatcher = Pattern.compile("in (\\d+)\\s*(min|minute|mins|minutes)").matcher(lower)
        if (inMinutesMatcher.find()) {
            val mins = inMinutesMatcher.group(1)?.toIntOrNull() ?: 10
            cal.add(Calendar.MINUTE, mins)
            val title = prompt.replace(Regex("in \\d+\\s*(min|minute|mins|minutes)", RegexOption.IGNORE_CASE), "")
                .removePrefix("schedule ").removePrefix("remind me to ").trim()
            return Pair(cal.timeInMillis, title)
        }

        val inHoursMatcher = Pattern.compile("in (\\d+)\\s*(hr|hour|hrs|hours)").matcher(lower)
        if (inHoursMatcher.find()) {
            val hrs = inHoursMatcher.group(1)?.toIntOrNull() ?: 1
            cal.add(Calendar.HOUR_OF_DAY, hrs)
            val title = prompt.replace(Regex("in \\d+\\s*(hr|hour|hrs|hours)", RegexOption.IGNORE_CASE), "")
                .removePrefix("schedule ").removePrefix("remind me to ").trim()
            return Pair(cal.timeInMillis, title)
        }

        // 2. "at [hour]:[minute] [am/pm]" or "at [hour] [am/pm]"
        val atTimeMatcher = Pattern.compile("at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(lower)
        if (atTimeMatcher.find()) {
            var hour = atTimeMatcher.group(1)?.toIntOrNull() ?: 9
            val minute = atTimeMatcher.group(2)?.toIntOrNull() ?: 0
            val amPm = atTimeMatcher.group(3)

            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0

            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)

            if (lower.contains("tomorrow")) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            } else if (cal.timeInMillis <= now) {
                // If the specified time already passed today, assume tomorrow
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }

            var title = prompt.replace(Regex("at \\d{1,2}(?::\\d{2})?\\s*(am|pm)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("tomorrow", RegexOption.IGNORE_CASE), "")
                .removePrefix("schedule ").removePrefix("remind me to ").trim()

            if (title.isBlank()) title = "Scheduled Event"
            return Pair(cal.timeInMillis, title)
        }

        // Default fallback: 1 hour from now
        cal.add(Calendar.HOUR_OF_DAY, 1)
        val defaultTitle = prompt.removePrefix("schedule ").removePrefix("remind me to ").trim()
        return Pair(cal.timeInMillis, defaultTitle.ifBlank { "New Activity" })
    }

    private fun generateConversationalResponse(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") ->
                "Hello! I am OmniVoice, your personal Android AI assistant. Say 'Open [App Name]' to launch any app, or 'Schedule [Task] at [Time]' to set advance activities."
            lower.contains("who are you") || lower.contains("what can you do") ->
                "I can operate all your installed Android apps, schedule activities in advance with automated alarms, make calls, search the web, and run customized routines."
            lower.contains("time") ->
                "The current time is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(System.currentTimeMillis()) + "."
            lower.contains("date") || lower.contains("today") ->
                "Today is " + SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(System.currentTimeMillis()) + "."
            lower.contains("help") ->
                "You can say:\n• 'Open YouTube' or 'Launch Camera'\n• 'Schedule Workout tomorrow at 7 AM'\n• 'Remind me to drink water in 30 minutes'\n• 'Start morning routine'\n• 'Turn on Wi-Fi settings'"
            else ->
                "I heard: \"$prompt\". You can ask me to open apps, schedule future tasks, or automate device settings."
        }
    }

    private fun callGeminiApi(prompt: String, apiKey: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val systemInstruction = "You are OmniVoice, an intelligent Android AI voice assistant. Answer helpfully, concisely, and naturally in 1 to 2 sentences as if speaking aloud."

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "$systemInstruction\nUser: $prompt")
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e("GeminiEngine", "Gemini API error code: ${response.code}")
                return ""
            }
            val responseBody = response.body?.string() ?: return ""
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "").trim()
                }
            }
        }
        return ""
    }
}
