package com.geekify.android.innertube.pages

import com.geekify.android.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
