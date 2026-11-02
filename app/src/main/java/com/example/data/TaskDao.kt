package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY scheduledTimestamp ASC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE isEnabled = 1 AND isCompleted = 0 ORDER BY scheduledTimestamp ASC")
    fun getActiveTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE isEnabled = 1")
    suspend fun getAllEnabledTasksSync(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("UPDATE scheduled_tasks SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateTaskEnabled(id: Long, isEnabled: Boolean)

    @Query("UPDATE scheduled_tasks SET isCompleted = :isCompleted, lastExecutedAt = :executedAt, executionLog = :log WHERE id = :id")
    suspend fun markTaskExecuted(id: Long, isCompleted: Boolean, executedAt: Long, log: String)
}
