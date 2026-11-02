package com.example.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {

    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    fun getActiveTasks(): Flow<List<TaskEntity>> = taskDao.getActiveTasks()

    suspend fun getTaskById(id: Long): TaskEntity? = taskDao.getTaskById(id)

    suspend fun insertTask(task: TaskEntity): Long = taskDao.insertTask(task)

    suspend fun updateTask(task: TaskEntity) = taskDao.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskDao.deleteTask(task)

    suspend fun deleteTaskById(id: Long) = taskDao.deleteTaskById(id)

    suspend fun setTaskEnabled(id: Long, isEnabled: Boolean) = taskDao.updateTaskEnabled(id, isEnabled)

    suspend fun markExecuted(id: Long, isCompleted: Boolean, timestamp: Long, log: String) =
        taskDao.markTaskExecuted(id, isCompleted, timestamp, log)

    suspend fun getAllEnabledTasksSync(): List<TaskEntity> = taskDao.getAllEnabledTasksSync()
}
