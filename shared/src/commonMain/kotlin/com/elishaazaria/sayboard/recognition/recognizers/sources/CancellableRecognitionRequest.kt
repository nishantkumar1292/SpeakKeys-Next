package com.elishaazaria.sayboard.recognition.recognizers.sources

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Generation gate for synchronous [com.elishaazaria.sayboard.recognition.recognizers.Recognizer]
 * implementations backed by cancellable suspend I/O.
 *
 * The capture API is synchronous, so getFinalResult uses runBlocking. Registering that coroutine's
 * Job here lets a concurrent cancel immediately cancel Ktor I/O while generation checks prevent a
 * late response from becoming the result of a newer utterance.
 */
internal class CancellableRecognitionRequest {
    private val mutex = Mutex()
    private var generation = 0L
    private var inFlight: Job? = null

    fun beginGeneration(): Long {
        val update = runBlocking {
            mutex.withLock {
                generation += 1
                GenerationUpdate(generation, inFlight).also { inFlight = null }
            }
        }
        update.previousJob?.cancel()
        return update.generation
    }

    fun cancelGeneration(): Long = beginGeneration()

    fun currentGeneration(): Long = runBlocking { mutex.withLock { generation } }

    fun isCurrent(expectedGeneration: Long): Boolean = runBlocking {
        mutex.withLock { generation == expectedGeneration }
    }

    fun ifCurrent(expectedGeneration: Long, action: () -> Unit): Boolean = runBlocking {
        mutex.withLock {
            if (generation != expectedGeneration) return@withLock false
            action()
            true
        }
    }

    suspend fun <T> runIfCurrent(
        expectedGeneration: Long,
        request: suspend () -> T,
    ): T? {
        val owner = currentCoroutineContext().job
        val registration = mutex.withLock {
            if (generation != expectedGeneration) {
                null
            } else {
                Registration(inFlight).also { inFlight = owner }
            }
        } ?: return null
        registration.previousJob?.cancel()

        try {
            val result = request()
            return mutex.withLock {
                result.takeIf { generation == expectedGeneration && inFlight === owner }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight === owner) inFlight = null
                }
            }
        }
    }

    private data class GenerationUpdate(
        val generation: Long,
        val previousJob: Job?,
    )

    private data class Registration(val previousJob: Job?)
}
