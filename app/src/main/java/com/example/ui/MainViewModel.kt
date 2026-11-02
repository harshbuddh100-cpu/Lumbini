package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.automation.AppManager
import com.example.data.AppDatabase
import com.example.data.InstalledApp
import com.example.data.RepeatMode
import com.example.data.TaskActionType
import com.example.data.TaskEntity
import com.example.data.TaskRepository
import com.example.data.VoiceCommandHistory
import com.example.scheduler.TaskScheduler
import com.example.voice.GeminiAssistantEngine
import com.example.voice.TextToSpeechHelper
import com.example.voice.VoiceRecognizerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repository: TaskRepository
    private val appManager = AppManager(app)
    private val taskScheduler = TaskScheduler(app)
    private val ttsHelper = TextToSpeechHelper(app)
    private val aiEngine = GeminiAssistantEngine()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<VoiceCommandHistory>>(emptyList())
    val commandHistory: StateFlow<List<VoiceCommandHistory>> = _commandHistory.asStateFlow()

    private val _latestCommandResult = MutableStateFlow<VoiceCommandHistory?>(null)
    val latestCommandResult: StateFlow<VoiceCommandHistory?> = _latestCommandResult.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val isSpeaking = ttsHelper.isSpeaking
    val isMuted = ttsHelper.isMuted

    val voiceRecognizer: VoiceRecognizerHelper = VoiceRecognizerHelper(
        context = app,
        onResultReceived = { spokenText ->
            processVoiceQuery(spokenText)
        },
        onErrorReceived = { errorMsg ->
            _errorMessage.value = errorMsg
        }
    )

    val isListening = voiceRecognizer.isListening
    val rmsLevel = voiceRecognizer.rmsLevel
    val partialTranscript = voiceRecognizer.partialText

    val allTasks: StateFlow<List<TaskEntity>>

    init {
        val database = AppDatabase.getInstance(app)
        repository = TaskRepository(database.taskDao())

        allTasks = repository.getAllTasks().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        refreshInstalledApps()
        seedInitialRoutinesIfEmpty()
    }

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun startListening() {
        _errorMessage.value = null
        voiceRecognizer.startListening()
    }

    fun stopListening() {
        voiceRecognizer.stopListening()
    }

    fun toggleMute() {
        ttsHelper.toggleMute()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = appManager.getInstalledApps()
        }
    }

    fun processVoiceQuery(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val parsed = aiEngine.parseAndProcess(query)

                var executionStatus = "Success"
                if (parsed.isScheduled && parsed.scheduledTimestamp != null) {
                    val task = TaskEntity(
                        title = parsed.taskTitle.ifBlank { "Voice Scheduled Activity" },
                        description = "Created by voice command: \"$query\"",
                        actionType = parsed.actionType,
                        targetParam = parsed.targetParam,
                        scheduledTimestamp = parsed.scheduledTimestamp,
                        repeatMode = parsed.repeatMode,
                        isEnabled = true
                    )
                    val id = repository.insertTask(task)
                    taskScheduler.scheduleTask(task.copy(id = id))
                    executionStatus = "Scheduled"
                } else {
                    // Immediate execution of action
                    val (success, message) = appManager.executeAction(parsed.actionType, parsed.targetParam)
                    executionStatus = if (success) message else "Failed: $message"
                }

                // Assistant Voice Feedback
                ttsHelper.speak(parsed.spokenResponse)

                val historyItem = VoiceCommandHistory(
                    userPrompt = query,
                    aiResponse = parsed.spokenResponse,
                    actionType = parsed.actionType,
                    targetParam = parsed.targetParam,
                    executionStatus = executionStatus
                )

                _latestCommandResult.value = historyItem
                _commandHistory.value = listOf(historyItem) + _commandHistory.value
            } catch (e: Exception) {
                val failMsg = "Could not process command: ${e.localizedMessage}"
                _errorMessage.value = failMsg
                ttsHelper.speak("I encountered an issue processing that command.")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun launchApp(packageName: String): Boolean {
        val success = appManager.launchAppByPackage(packageName)
        if (success) {
            ttsHelper.speak("Launching app")
        }
        return success
    }

    fun scheduleTask(
        title: String,
        actionType: TaskActionType,
        targetParam: String,
        timestamp: Long,
        repeatMode: RepeatMode
    ) {
        viewModelScope.launch {
            val task = TaskEntity(
                title = title,
                description = "Custom schedule",
                actionType = actionType,
                targetParam = targetParam,
                scheduledTimestamp = timestamp,
                repeatMode = repeatMode,
                isEnabled = true
            )
            val id = repository.insertTask(task)
            val savedTask = task.copy(id = id)
            taskScheduler.scheduleTask(savedTask)
            ttsHelper.speak("Activity scheduled for $title")
        }
    }

    fun toggleTaskEnabled(task: TaskEntity) {
        viewModelScope.launch {
            val nextState = !task.isEnabled
            repository.setTaskEnabled(task.id, nextState)
            if (nextState) {
                taskScheduler.scheduleTask(task.copy(isEnabled = true))
            } else {
                taskScheduler.cancelTask(task.id)
            }
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            taskScheduler.cancelTask(taskId)
            repository.deleteTaskById(taskId)
        }
    }

    fun executeTaskNow(task: TaskEntity) {
        viewModelScope.launch {
            val (success, message) = appManager.executeAction(task.actionType, task.targetParam)
            val now = System.currentTimeMillis()
            val log = if (success) "Executed manually at $now" else "Failed: $message"
            repository.markExecuted(task.id, isCompleted = task.repeatMode == RepeatMode.ONCE, timestamp = now, log = log)
            ttsHelper.speak("Executing ${task.title}")
        }
    }

    fun executeRoutine(routineKey: String) {
        viewModelScope.launch {
            when (routineKey) {
                "morning" -> {
                    ttsHelper.speak("Good morning! Here is your daily routine: Opening your calendar and news.")
                    appManager.executeAction(TaskActionType.SYSTEM_SETTING, "volume")
                    appManager.executeAction(TaskActionType.OPEN_URL, "today weather and top news")
                }
                "focus" -> {
                    ttsHelper.speak("Focus Mode activated. Muting non-essential sounds.")
                    appManager.executeAction(TaskActionType.SYSTEM_SETTING, "sound")
                }
                "workout" -> {
                    ttsHelper.speak("Workout started! Opening your music player and timer.")
                    val musicApp = appManager.findAppByName("spotify") ?: appManager.findAppByName("youtube")
                    if (musicApp != null) {
                        appManager.launchAppByPackage(musicApp.packageName)
                    }
                    appManager.executeAction(TaskActionType.SYSTEM_ALARM, "")
                }
                "night" -> {
                    ttsHelper.speak("Night mode activated. Setting your morning alarm.")
                    appManager.executeAction(TaskActionType.SYSTEM_ALARM, "")
                }
            }
        }
    }

    private fun seedInitialRoutinesIfEmpty() {
        viewModelScope.launch {
            val existing = repository.getAllEnabledTasksSync()
            if (existing.isEmpty()) {
                val now = System.currentTimeMillis()
                val tomorrowMorning = now + (18 * 60 * 60 * 1000L) // roughly tomorrow morning
                val initialRoutines = listOf(
                    TaskEntity(
                        title = "Morning AI Briefing",
                        description = "Wake up briefing with weather, calendar, and news",
                        actionType = TaskActionType.ROUTINE,
                        targetParam = "morning",
                        scheduledTimestamp = tomorrowMorning,
                        repeatMode = RepeatMode.DAILY,
                        isEnabled = true
                    ),
                    TaskEntity(
                        title = "Hydration & Health Break",
                        description = "Take 5 minutes to stretch and drink water",
                        actionType = TaskActionType.SPOKEN_REMINDER,
                        targetParam = "Drink a glass of water and stretch",
                        scheduledTimestamp = now + (2 * 60 * 60 * 1000L),
                        repeatMode = RepeatMode.DAILY,
                        isEnabled = true
                    )
                )
                for (task in initialRoutines) {
                    val id = repository.insertTask(task)
                    taskScheduler.scheduleTask(task.copy(id = id))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognizer.destroy()
        ttsHelper.shutdown()
    }
}
