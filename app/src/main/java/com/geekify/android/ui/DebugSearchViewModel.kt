package com.geekify.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.model.SearchResponse
import com.geekify.android.data.model.SearchType
import com.geekify.android.data.source.MusicResult
import com.geekify.android.data.source.MusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugSearchState(val query: String = "", val loading: Boolean = false, val response: SearchResponse? = null, val error: String? = null)

@HiltViewModel
class DebugSearchViewModel @Inject constructor(private val source: MusicSource) : ViewModel() {
    private val mutableState = MutableStateFlow(DebugSearchState())
    val state = mutableState.asStateFlow()
    fun setQuery(value: String) { mutableState.value = mutableState.value.copy(query = value) }
    fun search(type: SearchType? = null) = viewModelScope.launch {
        val query = mutableState.value.query.trim(); if (query.isEmpty()) return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        mutableState.value = when (val result = source.search(query, type)) {
            is MusicResult.Success -> mutableState.value.copy(loading = false, response = result.value)
            is MusicResult.Failure -> mutableState.value.copy(loading = false, error = result.message)
        }
    }
}
