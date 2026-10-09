package com.geekify.android.innertube.pages

import com.geekify.android.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
