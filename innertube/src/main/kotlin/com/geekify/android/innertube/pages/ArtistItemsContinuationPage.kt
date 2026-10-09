package com.geekify.android.innertube.pages

import com.geekify.android.innertube.models.YTItem

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
