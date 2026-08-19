// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.os.Handler
import android.os.Looper

internal fun interface WatchdogTask {
    fun cancel()
}

internal fun interface WatchdogScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): WatchdogTask
}

internal class MainThreadWatchdogScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : WatchdogScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): WatchdogTask {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return WatchdogTask { handler.removeCallbacks(runnable) }
    }
}

/**
 * A one-shot timeout whose deadline can be moved forward by progress. Generation checks make a
 * callback harmless even if an OEM Handler implementation races with [WatchdogTask.cancel].
 * Callers control this object from the main thread.
 */
internal class ResettableWatchdog(
    private val scheduler: WatchdogScheduler,
    private val timeoutMillis: Long,
    private val onTimeout: () -> Unit,
) {
    private var generation = 0L
    private var task: WatchdogTask? = null

    init {
        require(timeoutMillis > 0L) { "Watchdog timeout must be positive" }
    }

    fun refresh() {
        val scheduledGeneration = ++generation
        task?.cancel()
        task = scheduler.schedule(timeoutMillis) {
            if (scheduledGeneration != generation) return@schedule
            task = null
            onTimeout()
        }
    }

    fun cancel() {
        generation++
        task?.cancel()
        task = null
    }
}
