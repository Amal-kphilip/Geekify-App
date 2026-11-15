package com.geekify.android.ui.components

import com.geekify.android.data.model.Thumbnail

private val SIZE_REGEX = Regex("w\\d+-h\\d+")

/** Select the largest supplied art and ask YouTube's CDN for a display-quality square image. */
fun List<Thumbnail>.bestArtworkUrl(size: Int = 1200): String? =
    maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
        ?.url
        ?.replace(SIZE_REGEX, "w$size-h$size")
