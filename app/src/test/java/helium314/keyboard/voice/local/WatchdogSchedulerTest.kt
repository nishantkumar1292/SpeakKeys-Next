// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchdogSchedulerTest {
    @Test
    fun timeoutFiresOnceAtTheDeadline() {
        val scheduler = FakeWatchdogScheduler()
        var timeouts = 0
        val watchdog = ResettableWatchdog(scheduler, 5_000L) { timeouts++ }

        watchdog.refresh()
        scheduler.advanceBy(4_999L)
        assertEquals(0, timeouts)
        scheduler.advanceBy(1L)
        assertEquals(1, timeouts)
        scheduler.advanceBy(20_000L)
        assertEquals(1, timeouts)
    }

    @Test
    fun progressRefreshesTheDeadlineWithoutCappingTotalTime() {
        val scheduler = FakeWatchdogScheduler()
        var timeouts = 0
        val watchdog = ResettableWatchdog(scheduler, 5_000L) { timeouts++ }

        watchdog.refresh()
        repeat(6) {
            scheduler.advanceBy(4_000L)
            watchdog.refresh()
        }
        assertEquals(0, timeouts)

        scheduler.advanceBy(4_999L)
        assertEquals(0, timeouts)
        scheduler.advanceBy(1L)
        assertEquals(1, timeouts)
    }

    @Test
    fun cancellationAndStaleTasksCannotFire() {
        val scheduler = FakeWatchdogScheduler(runCancelledTasks = true)
        var timeouts = 0
        val watchdog = ResettableWatchdog(scheduler, 5_000L) { timeouts++ }

        watchdog.refresh()
        scheduler.advanceBy(1_000L)
        watchdog.refresh()
        watchdog.cancel()
        scheduler.advanceBy(10_000L)

        assertEquals(0, timeouts)
    }
}

internal class FakeWatchdogScheduler(
    private val runCancelledTasks: Boolean = false,
) : WatchdogScheduler {
    private data class ScheduledTask(
        val deadlineMillis: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private var nowMillis = 0L
    private var nextSequence = 0L
    private val tasks = mutableListOf<ScheduledTask>()

    override fun schedule(delayMillis: Long, action: () -> Unit): WatchdogTask {
        val scheduled = ScheduledTask(nowMillis + delayMillis, nextSequence++, action)
        tasks += scheduled
        return WatchdogTask { scheduled.cancelled = true }
    }

    fun advanceBy(deltaMillis: Long) {
        require(deltaMillis >= 0L)
        val destination = nowMillis + deltaMillis
        while (true) {
            val next = tasks
                .filter { it.deadlineMillis <= destination }
                .minWithOrNull(compareBy(ScheduledTask::deadlineMillis, ScheduledTask::sequence))
                ?: break
            tasks.remove(next)
            nowMillis = next.deadlineMillis
            if (!next.cancelled || runCancelledTasks) next.action()
        }
        nowMillis = destination
    }
}
