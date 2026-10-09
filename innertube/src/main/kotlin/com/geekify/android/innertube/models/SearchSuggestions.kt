package com.geekify.android.innertube.models

data class SearchSuggestions(
    val queries: List<String>,
    val recommendedItems: List<YTItem>,
)
