package com.geekify.android.data.local

import com.geekify.android.data.source.MusicResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs a disk / database operation off the main thread and converts any failure (full disk, corrupt
 * row, closed database...) into [MusicResult.Failure], so repositories report errors instead of crashing.
 * Cancellation is never swallowed.
 */
suspend fun <T> safeDbCall(dispatcher: CoroutineDispatcher, block: suspend () -> T): MusicResult<T> =
    withContext(dispatcher) {
        try {
            MusicResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MusicResult.Failure(e.message ?: "Could not read or write local data.")
        }
    }
