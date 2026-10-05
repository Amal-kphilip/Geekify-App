package com.geekify.android.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What a screen renders: a spinner, the data, or a recoverable error. ViewModels expose this, never raw exceptions. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val throwable: Throwable) : UiState<Nothing> {
        val message: String get() = throwable.message ?: "Something went wrong. Please try again."
    }
}

/** Turns a database Flow into UiState: Loading first, then Success for every change, or Error if the read fails. */
fun <T> Flow<T>.asUiState(): Flow<UiState<T>> =
    map<T, UiState<T>> { UiState.Success(it) }
        .onStart { emit(UiState.Loading) }
        .catch { emit(UiState.Error(it)) }
