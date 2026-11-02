package com.example

import com.example.data.Converters
import com.example.data.RepeatMode
import com.example.data.TaskActionType
import com.example.data.TaskEntity
import com.example.scheduler.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testTaskSchedulerFutureCalculation() {
        val now = System.currentTimeMillis()
        val nextDaily = TaskScheduler.calculateNextTrigger(now, now - 1000L, RepeatMode.DAILY)
        assertTrue("Daily recurring trigger must be in the future", nextDaily > now)

        val nextWeekly = TaskScheduler.calculateNextTrigger(now, now - 5000L, RepeatMode.WEEKLY)
        assertTrue("Weekly recurring trigger must be in the future", nextWeekly > now)
    }

    @Test
    fun testRoomTypeConverters() {
        val converters = Converters()

        assertEquals("LAUNCH_APP", converters.fromActionType(TaskActionType.LAUNCH_APP))
        assertEquals(TaskActionType.LAUNCH_APP, converters.toActionType("LAUNCH_APP"))
        assertEquals(TaskActionType.SPOKEN_REMINDER, converters.toActionType("INVALID_UNKNOWN"))

        assertEquals("DAILY", converters.fromRepeatMode(RepeatMode.DAILY))
        assertEquals(RepeatMode.DAILY, converters.toRepeatMode("DAILY"))
        assertEquals(RepeatMode.ONCE, converters.toRepeatMode("INVALID_MODE"))
    }

    @Test
    fun testTaskEntityDefaultValues() {
        val task = TaskEntity(
            title = "Morning Alarm",
            actionType = TaskActionType.LAUNCH_APP,
            targetParam = "com.google.android.youtube",
            scheduledTimestamp = System.currentTimeMillis() + 60000
        )
        assertNotNull(task)
        assertEquals(RepeatMode.ONCE, task.repeatMode)
        assertTrue(task.isEnabled)
    }
}
