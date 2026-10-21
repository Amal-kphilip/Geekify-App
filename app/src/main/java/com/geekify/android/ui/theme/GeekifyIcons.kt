package com.geekify.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unified Cupertino / SF-Symbol style vector icon set used across Geekify.
 */
object GeekifyIcons {

    private fun filledVector(
        name: String,
        pathData: String,
        viewportWidth: Float = 24f,
        viewportHeight: Float = 24f,
        defaultWidth: Dp = 24.dp,
        defaultHeight: Dp = 24.dp,
        evenOdd: Boolean = false,
        autoMirror: Boolean = false
    ): ImageVector {
        val fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        return ImageVector.Builder(
            name = name,
            defaultWidth = defaultWidth,
            defaultHeight = defaultHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            autoMirror = autoMirror
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            pathFillType = fillType,
            fill = SolidColor(Color.White)
        ).build()
    }

    private fun multiFilledVector(
        name: String,
        paths: List<String>,
        viewportWidth: Float = 24f,
        viewportHeight: Float = 24f,
        evenOdd: Boolean = false
    ): ImageVector {
        val fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
        for (p in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                pathFillType = fillType,
                fill = SolidColor(Color.White)
            )
        }
        return builder.build()
    }

    private fun strokedVector(
        name: String,
        paths: List<String>,
        strokeWidth: Float = 2f,
        viewportWidth: Float = 24f,
        viewportHeight: Float = 24f,
        autoMirror: Boolean = false
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            autoMirror = autoMirror
        )
        for (p in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    // ---- Navigation & Primary Tabs ----

    val Home: ImageVector by lazy {
        filledVector(
            name = "Home",
            viewportWidth = 56f,
            viewportHeight = 56f,
            pathData = "M11.035,50C9.436,50 8.197,49.574 7.318,48.722C6.439,47.87 6,46.686 6,45.17L6,25.242C6,24.344 6.14,23.598 6.419,23.005C6.698,22.412 7.187,21.828 7.886,21.254L25.189,7.097C25.642,6.732 26.097,6.457 26.554,6.274C27.011,6.091 27.493,6 28,6C28.507,6 28.989,6.091 29.446,6.274C29.903,6.457 30.359,6.732 30.812,7.097L48.114,21.254C48.813,21.828 49.302,22.412 49.581,23.005C49.86,23.598 50,24.344 50,25.242L50,45.17C50,46.686 49.561,47.87 48.682,48.722C47.803,49.574 46.564,50 44.965,50L35.709,50C35.279,50 34.921,49.863 34.637,49.59C34.353,49.316 34.211,48.966 34.211,48.539L34.211,35.506C34.211,35.057 34.068,34.693 33.782,34.414C33.496,34.135 33.117,33.995 32.646,33.995L23.354,33.995C22.89,33.995 22.513,34.135 22.223,34.414C21.934,34.693 21.789,35.057 21.789,35.506L21.789,48.539C21.789,48.966 21.648,49.316 21.368,49.59C21.088,49.863 20.729,50 20.291,50L11.035,50Z"
        )
    }

    val HomeOutlined: ImageVector get() = Home

    val Library: ImageVector by lazy {
        filledVector(
            name = "Library",
            viewportWidth = 56f,
            viewportHeight = 56f,
            pathData = "M37.3076,2.402C37.2092,0.8448 36.3236,0 34.6671,0L19.9721,0C18.3156,0 17.43,0.8448 17.3316,2.402L37.3076,2.402ZM40.7846,8.0341C40.5386,6.3942 39.7349,5.4334 37.9473,5.4334L16.5443,5.4334C14.7566,5.4334 13.953,6.3942 13.707,8.0341L40.7846,8.0341ZM40.1121,49C43.8515,49 46,46.83 46,42.6058L46,18.0064C46,13.7989 43.8351,11.6288 39.6037,11.6288L15.3799,11.6288C11.1485,11.6288 9,13.7657 9,18.0064L9,42.6058C9,46.8465 11.1485,49 15.3799,49L40.1121,49ZM20.2673,41.9763C18.4304,41.9763 17.1348,40.7671 17.1348,39.0609C17.1348,37.3215 18.2172,36.1951 20.3657,35.7644L22.055,35.4165C22.465,35.3337 22.6618,35.0686 22.6618,34.5717L22.6126,22.2637C22.6126,21.6011 23.039,21.187 23.777,21.0213L33.9947,18.8844C35.2411,18.6193 35.766,18.9838 35.766,20.0605L35.766,35.2508C35.766,38.4645 33.3387,39.5909 31.7642,39.5909C29.9109,39.5909 28.566,38.3651 28.566,36.6755C28.566,34.9858 29.6649,33.9422 31.8626,33.4452L33.4699,33.0974C33.9455,32.998 34.1915,32.6667 34.1915,32.1863L34.1423,25.3283C34.1423,24.9141 33.9455,24.8147 33.4535,24.9141L24.8103,26.7197C24.3675,26.8191 24.3183,26.8854 24.3183,27.3824L24.3183,37.5865C24.3183,40.7671 21.8418,41.9763 20.2673,41.9763Z"
        )
    }

    val LibraryOutlined: ImageVector get() = Library

    val Search: ImageVector by lazy {
        filledVector(
            name = "Search",
            viewportWidth = 22.8984f,
            viewportHeight = 23.1211f,
            defaultWidth = 22.8984.dp,
            defaultHeight = 23.1211.dp,
            pathData = "M0.0,9.3516C0.0,14.5078 4.1953,18.7031 9.3516,18.7031C11.3906,18.7031 13.2539,18.0469 14.7891,16.9453L20.5547,22.7227C20.8242,22.9922 21.1758,23.1211 21.5508,23.1211C22.3477,23.1211 22.8984,22.5234 22.8984,21.7383C22.8984,21.3633 22.7578,21.0234 22.5117,20.7773L16.7812,15.0117C17.9883,13.4414 18.7031,11.4844 18.7031,9.3516C18.7031,4.1953 14.5078,0.0 9.3516,0.0C4.1953,0.0 0.0,4.1953 0.0,9.3516ZM2.0039,9.3516C2.0039,5.2969 5.2969,2.0039 9.3516,2.0039C13.4062,2.0039 16.6992,5.2969 16.6992,9.3516C16.6992,13.4062 13.4062,16.6992 9.3516,16.6992C5.2969,16.6992 2.0039,13.4062 2.0039,9.3516Z"
        )
    }

    val SearchOutlined: ImageVector get() = Search

    val Add: ImageVector by lazy {
        filledVector(
            name = "Add",
            evenOdd = true,
            pathData = "M12 3.75a.75.75 0 0 1 .75.75v6.75h6.75a.75.75 0 0 1 0 1.5h-6.75v6.75a.75.75 0 0 1-1.5 0v-6.75H4.5a.75.75 0 0 1 0-1.5h6.75V4.5a.75.75 0 0 1 .75-.75Z"
        )
    }

    val AddOutlined: ImageVector get() = Add

    val AddCircleOutline: ImageVector by lazy {
        strokedVector(
            name = "AddCircleOutline",
            strokeWidth = 1.8f,
            paths = listOf(
                "M12,3a9,9 0 1,0 0,18a9,9 0 1,0 0,-18",
                "M12,8v8",
                "M8,12h8"
            )
        )
    }

    // ---- Transport & Player Controls ----

    val Play: ImageVector by lazy {
        filledVector(
            name = "Play",
            viewportWidth = 38f,
            viewportHeight = 38f,
            pathData = "M7.5776,36C5.619,36 4.1567,34.6943 4,32.5008L4,5.4992C4.1567,3.3057 5.619,2 7.5776,2C8.5438,2 9.2227,2.2873 10.3195,2.8618L35.1536,15.5269C36.9293,16.4409 38,17.3287 38,19C38,20.6713 36.9293,21.5591 35.1536,22.4731L10.3195,35.1382C9.2227,35.7127 8.5438,36 7.5776,36Z"
        )
    }

    val PlayCircleFilled: ImageVector by lazy {
        filledVector(
            name = "PlayCircleFilled",
            evenOdd = true,
            pathData = "M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12Zm14.024-.983a1.125 1.125 0 0 1 0 1.966l-5.603 3.113A1.125 1.125 0 0 1 9 15.113V8.887c0-.857.921-1.4 1.671-.983l5.603 3.113Z"
        )
    }

    val Pause: ImageVector by lazy {
        filledVector(
            name = "Pause",
            viewportWidth = 38f,
            viewportHeight = 38f,
            pathData = "M8.7384,36C6.3594,36 5,34.5857 5,32.4261L5,5.593C5,3.4143 6.3594,2 8.7384,2L12.911,2C15.2711,2 16.6305,3.4143 16.6305,5.593L16.6305,32.4261C16.6305,34.6048 15.2711,36 12.911,36L8.7384,36ZM25.089,36C22.7289,36 21.3695,34.6048 21.3695,32.4261L21.3695,5.593C21.3695,3.4143 22.7289,2 25.089,2L29.2616,2C31.6406,2 33,3.4143 33,5.593L33,32.4261C33,34.5857 31.6406,36 29.2616,36L25.089,36Z"
        )
    }

    val PauseCircleFilled: ImageVector by lazy {
        filledVector(
            name = "PauseCircleFilled",
            evenOdd = true,
            pathData = "M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12ZM9 8.25a.75.75 0 0 0-.75.75v6c0 .414.336.75.75.75h.75a.75.75 0 0 0 .75-.75V9a.75.75 0 0 0-.75-.75H9Zm5.25 0a.75.75 0 0 0-.75.75v6c0 .414.336.75.75.75H15a.75.75 0 0 0 .75-.75V9a.75.75 0 0 0-.75-.75h-.75Z"
        )
    }

    val SkipNext: ImageVector by lazy {
        filledVector(
            name = "SkipNext",
            viewportWidth = 40f,
            viewportHeight = 34f,
            pathData = "M21.3119,28.0396C21.8317,28.0396 22.3218,27.8614 22.901,27.5198L37.5297,19.0396C38.4356,18.5198 39,17.9703 39,17.0198C39,16.0545 38.4356,15.5198 37.5297,15L22.901,6.5198C22.3218,6.1782 21.8317,6 21.3119,6C20.3762,6 19.4851,6.698 19.4851,8.0644L19.4851,16.6188C19.3515,15.9059 18.8168,15.4455 18.0594,15L3.4158,6.5198C2.8515,6.1782 2.3614,6 1.8267,6C0.8911,6 0,6.698 0,8.0644L0,25.9752C0,27.3416 0.8911,28.0396 1.8267,28.0396C2.3614,28.0396 2.8515,27.8614 3.4158,27.5198L18.0594,19.0396C18.8168,18.5941 19.3515,18.1337 19.4851,17.4208L19.4851,25.9752C19.4851,27.3416 20.3762,28.0396 21.3119,28.0396Z"
        )
    }

    val FastForward: ImageVector get() = SkipNext

    val SkipPrevious: ImageVector by lazy {
        filledVector(
            name = "SkipPrevious",
            viewportWidth = 40f,
            viewportHeight = 34f,
            pathData = "M17.6881,28.0396C17.1683,28.0396 16.6782,27.8614 16.099,27.5198L1.4703,19.0396C0.5644,18.5198 0,17.9703 0,17.0198C0,16.0545 0.5644,15.5198 1.4703,15L16.099,6.5198C16.6782,6.1782 17.1683,6 17.6881,6C18.6238,6 19.5149,6.698 19.5149,8.0644L19.5149,16.6188C19.6485,15.9059 20.1832,15.4455 20.9406,15L35.5842,6.5198C36.1485,6.1782 36.6386,6 37.1733,6C38.1089,6 39,6.698 39,8.0644L39,25.9752C39,27.3416 38.1089,28.0396 37.1733,28.0396C36.6386,28.0396 36.1485,27.8614 35.5842,27.5198L20.9406,19.0396C20.1832,18.5941 19.6485,18.1337 19.5149,17.4208L19.5149,25.9752C19.5149,27.3416 18.6238,28.0396 17.6881,28.0396Z"
        )
    }

    val Shuffle: ImageVector by lazy {
        strokedVector(
            name = "Shuffle",
            paths = listOf(
                "m18 14 4 4-4 4",
                "m18 2 4 4-4 4",
                "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22",
                "M2 6h1.972a4 4 0 0 1 3.6 2.2",
                "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45"
            )
        )
    }

    val Repeat: ImageVector by lazy {
        strokedVector(
            name = "Repeat",
            paths = listOf(
                "m17 2 4 4-4 4",
                "M3 11v-1a4 4 0 0 1 4-4h14",
                "m7 22-4-4 4-4",
                "M21 13v1a4 4 0 0 1-4 4H3"
            )
        )
    }

    val RepeatOne: ImageVector by lazy {
        strokedVector(
            name = "RepeatOne",
            paths = listOf(
                "m17 2 4 4-4 4",
                "M3 11v-1a4 4 0 0 1 4-4h14",
                "m7 22-4-4 4-4",
                "M21 13v1a4 4 0 0 1-4 4H3",
                "M11 10h1v4"
            )
        )
    }

    val QueueMusic: ImageVector by lazy {
        filledVector(
            name = "QueueMusic",
            viewportWidth = 28f,
            viewportHeight = 28f,
            autoMirror = true,
            pathData = "M22.8,18.5c0.7,0 1.2,0.5 1.2,1.2c0,0.7 -0.5,1.2 -1.2,1.2H10.1c-0.7,0 -1.2,-0.5 -1.2,-1.2c0,-0.7 0.5,-1.2 1.2,-1.2H22.8zM5.5,21.2c-0.9,0 -1.5,-0.7 -1.5,-1.5c0,-0.8 0.7,-1.5 1.5,-1.5c0.8,0 1.5,0.7 1.5,1.5C7,20.5 6.4,21.2 5.5,21.2zM22.8,12.4c0.7,0 1.2,0.5 1.2,1.2c0,0.7 -0.5,1.2 -1.2,1.2H10.1c-0.7,0 -1.2,-0.5 -1.2,-1.2c0,-0.7 0.5,-1.2 1.2,-1.2H22.8zM5.5,15.1c-0.8,0 -1.5,-0.7 -1.5,-1.5c0,-0.9 0.7,-1.5 1.5,-1.5c0.8,0 1.5,0.7 1.5,1.5C7,14.4 6.4,15.1 5.5,15.1zM22.8,6.3c0.7,0 1.2,0.5 1.2,1.2c0,0.7 -0.5,1.2 -1.2,1.2H10.1c-0.7,0 -1.2,-0.5 -1.2,-1.2c0,-0.7 0.5,-1.2 1.2,-1.2H22.8zM5.5,9C4.7,9 4,8.3 4,7.5C4,6.7 4.7,6 5.5,6C6.4,6 7,6.7 7,7.5C7,8.3 6.4,9 5.5,9z"
        )
    }

    val Lyrics: ImageVector by lazy {
        filledVector(
            name = "Lyrics",
            viewportWidth = 28f,
            viewportHeight = 28f,
            autoMirror = true,
            pathData = "M14.4,12.2c0,-1.1 0.8,-1.9 2,-1.9c1.3,0 2.1,1 2.1,2.4c0,2 -1.7,3.2 -2.7,3.2c-0.3,0 -0.5,-0.2 -0.5,-0.4c0,-0.2 0.1,-0.4 0.4,-0.4c0.8,-0.2 1.4,-0.7 1.7,-1.4h-0.2c-0.2,0.3 -0.6,0.4 -1.1,0.4C15.1,14 14.4,13.2 14.4,12.2zM9.5,12.2c0,-1.1 0.8,-1.9 2,-1.9c1.3,0 2.1,1 2.1,2.4c0,2 -1.7,3.2 -2.7,3.2c-0.3,0 -0.5,-0.2 -0.5,-0.4c0,-0.2 0.1,-0.4 0.4,-0.4c0.8,-0.2 1.5,-0.7 1.7,-1.4h-0.2c-0.2,0.3 -0.6,0.4 -1.1,0.4C10.2,14 9.5,13.2 9.5,12.2zM10.4,21.4l2.8,-2.7c0.6,-0.6 0.9,-0.7 1.6,-0.7h4.6c1.3,0 2.1,-0.8 2.1,-2.1V9.4c0,-1.4 -0.8,-2.1 -2.1,-2.1H8.5C7.2,7.3 6.4,8 6.4,9.4v6.5c0,1.3 0.8,2.1 2.1,2.1h1c0.6,0 0.9,0.3 0.9,0.9V21.4zM9.9,24c-0.9,0 -1.5,-0.6 -1.5,-1.6v-2H7.9C5.4,20.3 4,19 4,16.5V9c0,-2.5 1.5,-4 4.1,-4h11.8C22.5,5 24,6.4 24,9v7.6c0,2.5 -1.5,3.8 -4.1,3.8h-5.1l-3.1,2.7C11,23.7 10.5,24 9.9,24z"
        )
    }

    val Favorite: ImageVector by lazy {
        filledVector(
            name = "Favorite",
            pathData = "m11.645 20.91-.007-.003-.022-.012a15.247 15.247 0 0 1-.383-.218 25.18 25.18 0 0 1-4.244-3.17C4.688 15.36 2.25 12.174 2.25 8.25 2.25 5.322 4.714 3 7.688 3A5.5 5.5 0 0 1 12 5.052 5.5 5.5 0 0 1 16.313 3c2.973 0 5.437 2.322 5.437 5.25 0 3.925-2.438 7.111-4.739 9.256a25.175 25.175 0 0 1-4.244 3.17 15.247 15.247 0 0 1-.383.219l-.022.012-.007.004-.003.001a.752.752 0 0 1-.704 0l-.003-.001Z"
        )
    }

    val FavoriteBorder: ImageVector by lazy {
        strokedVector(
            name = "FavoriteBorder",
            strokeWidth = 1.6f,
            paths = listOf("M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z")
        )
    }

    val Timer: ImageVector by lazy {
        filledVector(
            name = "Timer",
            evenOdd = true,
            pathData = "M12 2.25c-5.385 0-9.75 4.365-9.75 9.75s4.365 9.75 9.75 9.75 9.75-4.365 9.75-9.75S17.385 2.25 12 2.25ZM12.75 6a.75.75 0 0 0-1.5 0v6c0 .414.336.75.75.75h4.5a.75.75 0 0 0 0-1.5h-3.75V6Z"
        )
    }

    val VolumeUp: ImageVector by lazy {
        multiFilledVector(
            name = "VolumeUp",
            paths = listOf(
                "M13.5 4.06c0-1.336-1.616-2.005-2.56-1.06l-4.5 4.5H4.508c-1.141 0-2.318.664-2.66 1.905A9.76 9.76 0 0 0 1.5 12c0 .898.121 1.768.35 2.595.341 1.24 1.518 1.905 2.659 1.905h1.93l4.5 4.5c.945.945 2.561.276 2.561-1.06V4.06ZM18.584 5.106a.75.75 0 0 1 1.06 0c3.808 3.807 3.808 9.98 0 13.788a.75.75 0 0 1-1.06-1.06 8.25 8.25 0 0 0 0-11.668.75.75 0 0 1 0-1.06Z",
                "M15.932 7.757a.75.75 0 0 1 1.061 0 6 6 0 0 1 0 8.486.75.75 0 0 1-1.06-1.061 4.5 4.5 0 0 0 0-6.364.75.75 0 0 1 0-1.06Z"
            )
        )
    }

    val MusicNote: ImageVector by lazy {
        filledVector(
            name = "MusicNote",
            evenOdd = true,
            pathData = "M19.952 1.651a.75.75 0 0 1 .298.599V16.303a3 3 0 0 1-2.176 2.884l-1.32.377a2.553 2.553 0 1 1-1.403-4.909l2.311-.66a1.5 1.5 0 0 0 1.088-1.442V6.994l-9 2.572v9.737a3 3 0 0 1-2.176 2.884l-1.32.377a2.553 2.553 0 1 1-1.402-4.909l2.31-.66a1.5 1.5 0 0 0 1.088-1.442V5.25a.75.75 0 0 1 .544-.721l10.5-3a.75.75 0 0 1 .658.122Z"
        )
    }

    val Audiotrack: ImageVector get() = MusicNote
    val LibraryMusic: ImageVector get() = Library

    val MoreHoriz: ImageVector by lazy {
        filledVector(
            name = "MoreHoriz",
            evenOdd = true,
            pathData = "M4.5 12a1.5 1.5 0 1 1 3 0 1.5 1.5 0 0 1-3 0Zm6 0a1.5 1.5 0 1 1 3 0 1.5 1.5 0 0 1-3 0Zm6 0a1.5 1.5 0 1 1 3 0 1.5 1.5 0 0 1-3 0Z"
        )
    }

    val PlaylistAdd: ImageVector by lazy {
        strokedVector(
            name = "PlaylistAdd",
            paths = listOf(
                "M16 5H3",
                "M11 12H3",
                "M11 19H3",
                "M18 12v8",
                "M14 16h8"
            )
        )
    }

    val ClearAll: ImageVector by lazy {
        strokedVector(
            name = "ClearAll",
            paths = listOf(
                "M5 7h14",
                "M7 12h12",
                "M9 17h10"
            )
        )
    }

    val Explicit: ImageVector by lazy {
        filledVector(
            name = "Explicit",
            viewportWidth = 960f,
            viewportHeight = 960f,
            pathData = "M360,680h240v-80L440,600v-80h160v-80L440,440v-80h160v-80L360,280v400ZM200,840q-33,0 -56.5,-23.5T120,760v-560q0,-33 23.5,-56.5T200,120h560q33,0 56.5,23.5T840,200v560q0,33 -23.5,56.5T760,840L200,840ZM200,760h560v-560L200,200v560ZM200,200v560,-560Z"
        )
    }

    // ---- Navigation & Actions ----

    val ArrowBack: ImageVector by lazy {
        strokedVector(
            name = "ArrowBack",
            autoMirror = true,
            paths = listOf(
                "m12 19-7-7 7-7",
                "M19 12H5"
            )
        )
    }

    val ChevronRight: ImageVector by lazy {
        strokedVector(
            name = "ChevronRight",
            autoMirror = true,
            paths = listOf("m9 18 6-6-6-6")
        )
    }

    val KeyboardArrowDown: ImageVector by lazy {
        strokedVector(
            name = "KeyboardArrowDown",
            paths = listOf("m6 9 6 6 6-6")
        )
    }

    val Close: ImageVector by lazy {
        strokedVector(
            name = "Close",
            paths = listOf(
                "M18 6 6 18",
                "m6 6 12 12"
            )
        )
    }

    val Clear: ImageVector get() = Close

    val Check: ImageVector by lazy {
        filledVector(
            name = "Check",
            evenOdd = true,
            pathData = "M19.916 4.626a.75.75 0 0 1 .208 1.04l-9 13.5a.75.75 0 0 1-1.154.114l-6-6a.75.75 0 0 1 1.06-1.06l5.353 5.353 8.493-12.74a.75.75 0 0 1 1.04-.207Z"
        )
    }

    val CheckCircle: ImageVector by lazy {
        filledVector(
            name = "CheckCircle",
            evenOdd = true,
            pathData = "M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12Zm13.36-1.814a.75.75 0 1 0-1.22-.872l-3.236 4.53L9.53 12.22a.75.75 0 0 0-1.06 1.06l2.25 2.25a.75.75 0 0 0 1.14-.094l3.75-5.25Z"
        )
    }

    val Verified: ImageVector get() = CheckCircle

    val Settings: ImageVector by lazy {
        strokedVector(
            name = "Settings",
            paths = listOf(
                "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
                "M9.0,12.0 a3.0,3.0 0 1,0 6.0,0 a3.0,3.0 0 1,0 -6.0,0"
            )
        )
    }

    val Delete: ImageVector by lazy {
        filledVector(
            name = "Delete",
            evenOdd = true,
            pathData = "M16.5 4.478v.227a48.816 48.816 0 0 1 3.878.512.75.75 0 1 1-.256 1.478l-.209-.035-1.005 13.07a3 3 0 0 1-2.991 2.77H8.084a3 3 0 0 1-2.991-2.77L4.087 6.66l-.209.035a.75.75 0 0 1-.256-1.478A48.567 48.567 0 0 1 7.5 4.705v-.227c0-1.564 1.213-2.9 2.816-2.951a52.662 52.662 0 0 1 3.369 0c1.603.051 2.815 1.387 2.815 2.951Zm-6.136-1.452a51.196 51.196 0 0 1 3.273 0C14.39 3.05 15 3.684 15 4.478v.113a49.488 49.488 0 0 0-6 0v-.113c0-.794.609-1.428 1.364-1.452Zm-.355 5.945a.75.75 0 1 0-1.5.058l.347 9a.75.75 0 1 0 1.499-.058l-.346-9Zm5.48.058a.75.75 0 1 0-1.498-.058l-.347 9a.75.75 0 0 0 1.5.058l.345-9Z"
        )
    }

    val DeleteForever: ImageVector get() = Delete
    val DeleteOutline: ImageVector get() = Delete
    val DeleteSweep: ImageVector get() = Delete

    val Edit: ImageVector by lazy {
        strokedVector(
            name = "Edit",
            paths = listOf(
                "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
                "m15 5 4 4"
            )
        )
    }

    val Refresh: ImageVector by lazy {
        filledVector(
            name = "Refresh",
            evenOdd = true,
            pathData = "M9.53 2.47a.75.75 0 0 1 0 1.06L4.81 8.25H15a6.75 6.75 0 0 1 0 13.5h-3a.75.75 0 0 1 0-1.5h3a5.25 5.25 0 1 0 0-10.5H4.81l4.72 4.72a.75.75 0 1 1-1.06 1.06l-6-6a.75.75 0 0 1 0-1.06l6-6a.75.75 0 0 1 1.06 0Z"
        )
    }

    val RestartAlt: ImageVector get() = Refresh

    // ---- Account, Settings & System Icons ----

    val Person: ImageVector by lazy {
        filledVector(
            name = "Person",
            evenOdd = true,
            pathData = "M7.5 6a4.5 4.5 0 1 1 9 0 4.5 4.5 0 0 1-9 0ZM3.751 20.105a8.25 8.25 0 0 1 16.498 0 .75.75 0 0 1-.437.695A18.683 18.683 0 0 1 12 22.5c-2.786 0-5.433-.608-7.812-1.7a.75.75 0 0 1-.437-.695Z"
        )
    }

    val AccountCircle: ImageVector by lazy {
        filledVector(
            name = "AccountCircle",
            evenOdd = true,
            pathData = "M18.685 19.097A9.723 9.723 0 0 0 21.75 12c0-5.385-4.365-9.75-9.75-9.75S2.25 6.615 2.25 12a9.723 9.723 0 0 0 3.065 7.097A9.716 9.716 0 0 0 12 21.75a9.716 9.716 0 0 0 6.685-2.653Zm-12.54-1.285A7.486 7.486 0 0 1 12 15a7.486 7.486 0 0 1 5.855 2.812A8.224 8.224 0 0 1 12 20.25a8.224 8.224 0 0 1-5.855-2.438ZM15.75 9a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0Z"
        )
    }

    val PersonAdd: ImageVector by lazy {
        filledVector(
            name = "PersonAdd",
            pathData = "M10.5 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM1.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 9 21.75c-2.676 0-5.216-.584-7.499-1.632ZM19.5 7.5a.75.75 0 0 1 .75.75v2.25h2.25a.75.75 0 0 1 0 1.5h-2.25v2.25a.75.75 0 0 1-1.5 0v-2.25H16.5a.75.75 0 0 1 0-1.5h2.25V8.25a.75.75 0 0 1 .75-.75Z"
        )
    }

    val Groups: ImageVector by lazy {
        multiFilledVector(
            name = "Groups",
            evenOdd = true,
            paths = listOf(
                "M8.25 6.75a3.75 3.75 0 1 1 7.5 0 3.75 3.75 0 0 1-7.5 0ZM15.75 9.75a3 3 0 1 1 6 0 3 3 0 0 1-6 0ZM2.25 9.75a3 3 0 1 1 6 0 3 3 0 0 1-6 0ZM6.31 15.117A6.745 6.745 0 0 1 12 12a6.745 6.745 0 0 1 6.709 7.498.75.75 0 0 1-.372.568A12.696 12.696 0 0 1 12 21.75c-2.305 0-4.47-.612-6.337-1.684a.75.75 0 0 1-.372-.568 6.787 6.787 0 0 1 1.019-4.38Z",
                "M5.082 14.254a8.287 8.287 0 0 0-1.308 5.135 9.687 9.687 0 0 1-1.764-.44l-.115-.04a.563.563 0 0 1-.373-.487l-.01-.121a3.75 3.75 0 0 1 3.57-4.047ZM20.226 19.389a8.287 8.287 0 0 0-1.308-5.135 3.75 3.75 0 0 1 3.57 4.047l-.01.121a.563.563 0 0 1-.373.486l-.115.04c-.567.2-1.156.349-1.764.441Z"
            )
        )
    }

    val History: ImageVector by lazy {
        filledVector(
            name = "History",
            viewportWidth = 960f,
            viewportHeight = 960f,
            pathData = "M349,651Q320,622 320,580Q320,538 349,509Q378,480 420,480Q437,480 452,485Q467,490 480,500L480,280L640,280L640,360L520,360L520,580Q520,622 491,651Q462,680 420,680Q378,680 349,651ZM480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480L160,480Q160,546 185,604.5Q210,663 253.5,706.5Q297,750 355.5,775.5Q414,801 480,801Q614,801 707,708Q800,615 800,481Q800,347 707,254Q614,161 480,161Q391,161 318.5,204.5Q246,248 204,320L320,320L320,400L80,400L80,160L160,160L160,240Q215,167 298,123.5Q381,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880Z"
        )
    }

    val BarChart: ImageVector by lazy {
        filledVector(
            name = "BarChart",
            pathData = "M18.375 2.25c-1.035 0-1.875.84-1.875 1.875v15.75c0 1.035.84 1.875 1.875 1.875h.75c1.035 0 1.875-.84 1.875-1.875V4.125c0-1.036-.84-1.875-1.875-1.875h-.75ZM9.75 8.625c0-1.036.84-1.875 1.875-1.875h.75c1.036 0 1.875.84 1.875 1.875v11.25c0 1.035-.84 1.875-1.875 1.875h-.75a1.875 1.875 0 0 1-1.875-1.875V8.625ZM3 13.125c0-1.036.84-1.875 1.875-1.875h.75c1.036 0 1.875.84 1.875 1.875v6.75c0 1.035-.84 1.875-1.875 1.875h-.75A1.875 1.875 0 0 1 3 19.875v-6.75Z"
        )
    }

    val Insights: ImageVector get() = BarChart

    val Shield: ImageVector by lazy {
        filledVector(
            name = "Shield",
            evenOdd = true,
            pathData = "M12.516 2.17a.75.75 0 0 0-1.032 0 11.209 11.209 0 0 1-7.877 3.08.75.75 0 0 0-.722.515A12.74 12.74 0 0 0 2.25 9.75c0 5.942 4.064 10.933 9.563 12.348a.749.749 0 0 0 .374 0c5.499-1.415 9.563-6.406 9.563-12.348 0-1.39-.223-2.73-.635-3.985a.75.75 0 0 0-.722-.516l-.143.001c-2.996 0-5.717-1.17-7.734-3.08Zm3.094 8.016a.75.75 0 1 0-1.22-.872l-3.236 4.53L9.53 12.22a.75.75 0 0 0-1.06 1.06l2.25 2.25a.75.75 0 0 0 1.14-.094l3.75-5.25Z"
        )
    }

    val Security: ImageVector get() = Shield
    val HealthAndSafety: ImageVector get() = Shield

    val MonitorHeart: ImageVector by lazy {
        strokedVector(
            name = "MonitorHeart",
            paths = listOf(
                "M22 12h-4l-3 9L9 3l-3 9H2"
            )
        )
    }

    val Logout: ImageVector by lazy {
        filledVector(
            name = "Logout",
            evenOdd = true,
            autoMirror = true,
            pathData = "M7.5 3.75A1.5 1.5 0 0 0 6 5.25v13.5a1.5 1.5 0 0 0 1.5 1.5h6a1.5 1.5 0 0 0 1.5-1.5V15a.75.75 0 0 1 1.5 0v3.75a3 3 0 0 1-3 3h-6a3 3 0 0 1-3-3V5.25a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3V9A.75.75 0 0 1 15 9V5.25a1.5 1.5 0 0 0-1.5-1.5h-6Zm5.03 4.72a.75.75 0 0 1 0 1.06l-1.72 1.72h10.94a.75.75 0 0 1 0 1.5H10.81l1.72 1.72a.75.75 0 1 1-1.06 1.06l-3-3a.75.75 0 0 1 0-1.06l3-3a.75.75 0 0 1 1.06 0Z"
        )
    }

    val Error: ImageVector by lazy {
        filledVector(
            name = "Error",
            evenOdd = true,
            pathData = "M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12ZM12 8.25a.75.75 0 0 1 .75.75v3.75a.75.75 0 0 1-1.5 0V9a.75.75 0 0 1 .75-.75Zm0 8.25a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Z"
        )
    }

    val ErrorOutline: ImageVector get() = Error
    val CloudDone: ImageVector get() = CheckCircle

    val Lock: ImageVector by lazy {
        filledVector(
            name = "Lock",
            evenOdd = true,
            pathData = "M12 1.5a5.25 5.25 0 0 0-5.25 5.25v3a3 3 0 0 0-3 3v6.75a3 3 0 0 0 3 3h10.5a3 3 0 0 0 3-3v-6.75a3 3 0 0 0-3-3v-3c0-2.9-2.35-5.25-5.25-5.25Zm3.75 8.25v-3a3.75 3.75 0 1 0-7.5 0v3h7.5Z"
        )
    }

    val Mail: ImageVector by lazy {
        multiFilledVector(
            name = "Mail",
            paths = listOf(
                "M1.5 8.67v8.58a3 3 0 0 0 3 3h15a3 3 0 0 0 3-3V8.67l-8.928 5.493a3 3 0 0 1-3.144 0L1.5 8.67Z",
                "M22.5 6.908V6.75a3 3 0 0 0-3-3h-15a3 3 0 0 0-3 3v.158l9.714 5.978a1.5 1.5 0 0 0 1.572 0L22.5 6.908Z"
            )
        )
    }

    val CameraAlt: ImageVector by lazy {
        multiFilledVector(
            name = "CameraAlt",
            evenOdd = true,
            paths = listOf(
                "M12 9a3.75 3.75 0 1 0 0 7.5A3.75 3.75 0 0 0 12 9Z",
                "M9.344 3.071a49.52 49.52 0 0 1 5.312 0c.967.052 1.83.585 2.332 1.39l.821 1.317c.24.383.645.643 1.11.71.386.054.77.113 1.152.177 1.432.239 2.429 1.493 2.429 2.909V18a3 3 0 0 1-3 3h-15a3 3 0 0 1-3-3V9.574c0-1.416.997-2.67 2.429-2.909.382-.064.766-.123 1.151-.178a1.56 1.56 0 0 0 1.11-.71l.822-1.315a2.942 2.942 0 0 1 2.332-1.39ZM6.75 12.75a5.25 5.25 0 1 1 10.5 0 5.25 5.25 0 0 1-10.5 0Zm12-1.5a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Z"
            )
        )
    }

    val Album: ImageVector by lazy {
        strokedVector(
            name = "Album",
            paths = listOf(
                "M2.0,12.0 a10.0,10.0 0 1,0 20.0,0 a10.0,10.0 0 1,0 -20.0,0",
                "M10.0,12.0 a2.0,2.0 0 1,0 4.0,0 a2.0,2.0 0 1,0 -4.0,0"
            )
        )
    }

    val GraphicEq: ImageVector by lazy {
        strokedVector(
            name = "GraphicEq",
            paths = listOf(
                "M2 10v3",
                "M6 6v11",
                "M10 3v18",
                "M14 8v7",
                "M18 5v13",
                "M22 10v3"
            )
        )
    }

    val Sliders: ImageVector by lazy {
        filledVector(
            name = "Sliders",
            pathData = "M18.75 12.75h1.5a.75.75 0 0 0 0-1.5h-1.5a.75.75 0 0 0 0 1.5ZM12 6a.75.75 0 0 1 .75-.75h7.5a.75.75 0 0 1 0 1.5h-7.5A.75.75 0 0 1 12 6ZM12 18a.75.75 0 0 1 .75-.75h7.5a.75.75 0 0 1 0 1.5h-7.5A.75.75 0 0 1 12 18ZM3.75 6.75h1.5a.75.75 0 1 0 0-1.5h-1.5a.75.75 0 0 0 0 1.5ZM5.25 18.75h-1.5a.75.75 0 0 1 0-1.5h1.5a.75.75 0 0 1 0 1.5ZM3 12a.75.75 0 0 1 .75-.75h7.5a.75.75 0 0 1 0 1.5h-7.5A.75.75 0 0 1 3 12ZM9 3.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5ZM12.75 12a2.25 2.25 0 1 1 4.5 0 2.25 2.25 0 0 1-4.5 0ZM9 15.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5Z"
        )
    }

    val AutoAwesome: ImageVector by lazy {
        filledVector(
            name = "AutoAwesome",
            evenOdd = true,
            pathData = "M9 4.5a.75.75 0 0 1 .721.544l.813 2.846a3.75 3.75 0 0 0 2.576 2.576l2.846.813a.75.75 0 0 1 0 1.442l-2.846.813a3.75 3.75 0 0 0-2.576 2.576l-.813 2.846a.75.75 0 0 1-1.442 0l-.813-2.846a3.75 3.75 0 0 0-2.576-2.576l-2.846-.813a.75.75 0 0 1 0-1.442l2.846-.813A3.75 3.75 0 0 0 7.466 7.89l.813-2.846A.75.75 0 0 1 9 4.5ZM18 1.5a.75.75 0 0 1 .728.568l.258 1.036c.236.94.97 1.674 1.91 1.91l1.036.258a.75.75 0 0 1 0 1.456l-1.036.258c-.94.236-1.674.97-1.91 1.91l-.258 1.036a.75.75 0 0 1-1.456 0l-.258-1.036a2.625 2.625 0 0 0-1.91-1.91l-1.036-.258a.75.75 0 0 1 0-1.456l1.036-.258a2.625 2.625 0 0 0 1.91-1.91l.258-1.036A.75.75 0 0 1 18 1.5ZM16.5 15a.75.75 0 0 1 .712.513l.394 1.183c.15.447.5.799.948.948l1.183.395a.75.75 0 0 1 0 1.422l-1.183.395c-.447.15-.799.5-.948.948l-.395 1.183a.75.75 0 0 1-1.422 0l-.395-1.183a1.5 1.5 0 0 0-.948-.948l-1.183-.395a.75.75 0 0 1 0-1.422l1.183-.395c.447-.15.799-.5.948-.948l.395-1.183A.75.75 0 0 1 16.5 15Z"
        )
    }

    val Flare: ImageVector get() = AutoAwesome

    val Apps: ImageVector by lazy {
        filledVector(
            name = "Apps",
            evenOdd = true,
            pathData = "M3 6a3 3 0 0 1 3-3h2.25a3 3 0 0 1 3 3v2.25a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6Zm9.75 0a3 3 0 0 1 3-3H18a3 3 0 0 1 3 3v2.25a3 3 0 0 1-3 3h-2.25a3 3 0 0 1-3-3V6ZM3 15.75a3 3 0 0 1 3-3h2.25a3 3 0 0 1 3 3V18a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-2.25Zm9.75 0a3 3 0 0 1 3-3H18a3 3 0 0 1 3 3V18a3 3 0 0 1-3 3h-2.25a3 3 0 0 1-3-3v-2.25Z"
        )
    }

    val Language: ImageVector by lazy {
        filledVector(
            name = "Language",
            pathData = "M21.721 12.752a9.711 9.711 0 0 0-.945-5.003 12.754 12.754 0 0 1-4.339 2.708 18.991 18.991 0 0 1-.214 4.772 17.165 17.165 0 0 0 5.498-2.477ZM14.634 15.55a17.324 17.324 0 0 0 .332-4.647c-.952.227-1.945.347-2.966.347-1.021 0-2.014-.12-2.966-.347a17.515 17.515 0 0 0 .332 4.647 17.385 17.385 0 0 0 5.268 0ZM9.772 17.119a18.963 18.963 0 0 0 4.456 0A17.182 17.182 0 0 1 12 21.724a17.18 17.18 0 0 1-2.228-4.605ZM7.777 15.23a18.87 18.87 0 0 1-.214-4.774 12.753 12.753 0 0 1-4.34-2.708 9.711 9.711 0 0 0-.944 5.004 17.165 17.165 0 0 0 5.498 2.477ZM21.356 14.752a9.765 9.765 0 0 1-7.478 6.817 18.64 18.64 0 0 0 1.988-4.718 18.627 18.627 0 0 0 5.49-2.098ZM2.644 14.752c1.682.971 3.53 1.688 5.49 2.099a18.64 18.64 0 0 0 1.988 4.718 9.765 9.765 0 0 1-7.478-6.816ZM13.878 2.43a9.755 9.755 0 0 1 6.116 3.986 11.267 11.267 0 0 1-3.746 2.504 18.63 18.63 0 0 0-2.37-6.49ZM12 2.276a17.152 17.152 0 0 1 2.805 7.121c-.897.23-1.837.353-2.805.353-.968 0-1.908-.122-2.805-.353A17.151 17.151 0 0 1 12 2.276ZM10.122 2.43a18.629 18.629 0 0 0-2.37 6.49 11.266 11.266 0 0 1-3.746-2.504 9.754 9.754 0 0 1 6.116-3.985Z"
        )
    }

    val Public: ImageVector get() = Language

    val Translate: ImageVector by lazy {
        filledVector(
            name = "Translate",
            evenOdd = true,
            pathData = "M9 2.25a.75.75 0 0 1 .75.75v1.506a49.384 49.384 0 0 1 5.343.371.75.75 0 1 1-.186 1.489c-.66-.083-1.323-.151-1.99-.206a18.67 18.67 0 0 1-2.97 6.323c.318.384.65.753 1 1.107a.75.75 0 0 1-1.07 1.052A18.902 18.902 0 0 1 9 13.687a18.823 18.823 0 0 1-5.656 4.482.75.75 0 0 1-.688-1.333 17.323 17.323 0 0 0 5.396-4.353A18.72 18.72 0 0 1 5.89 8.598a.75.75 0 0 1 1.388-.568A17.21 17.21 0 0 0 9 11.224a17.168 17.168 0 0 0 2.391-5.165 48.04 48.04 0 0 0-8.298.307.75.75 0 0 1-.186-1.489 49.159 49.159 0 0 1 5.343-.371V3A.75.75 0 0 1 9 2.25ZM15.75 9a.75.75 0 0 1 .68.433l5.25 11.25a.75.75 0 1 1-1.36.634l-1.198-2.567h-6.744l-1.198 2.567a.75.75 0 0 1-1.36-.634l5.25-11.25A.75.75 0 0 1 15.75 9Zm-2.672 8.25h5.344l-2.672-5.726-2.672 5.726Z"
        )
    }

    val Notifications: ImageVector by lazy {
        filledVector(
            name = "Notifications",
            evenOdd = true,
            pathData = "M5.25 9a6.75 6.75 0 0 1 13.5 0v.75c0 2.123.8 4.057 2.118 5.52a.75.75 0 0 1-.297 1.206c-1.544.57-3.16.99-4.831 1.243a3.75 3.75 0 1 1-7.48 0 24.585 24.585 0 0 1-4.831-1.244.75.75 0 0 1-.298-1.205A8.217 8.217 0 0 0 5.25 9.75V9Zm4.502 8.9a2.25 2.25 0 1 0 4.496 0 25.057 25.057 0 0 1-4.496 0Z"
        )
    }

    val Storage: ImageVector by lazy {
        multiFilledVector(
            name = "Storage",
            paths = listOf(
                "M21 6.375c0 2.692-4.03 4.875-9 4.875S3 9.067 3 6.375 7.03 1.5 12 1.5s9 2.183 9 4.875Z",
                "M12 12.75c2.685 0 5.19-.586 7.078-1.609a8.283 8.283 0 0 0 1.897-1.384c.016.121.025.244.025.368C21 12.817 16.97 15 12 15s-9-2.183-9-4.875c0-.124.009-.247.025-.368a8.285 8.285 0 0 0 1.897 1.384C6.809 12.164 9.315 12.75 12 12.75Z",
                "M12 16.5c2.685 0 5.19-.586 7.078-1.609a8.282 8.282 0 0 0 1.897-1.384c.016.121.025.244.025.368 0 2.692-4.03 4.875-9 4.875s-9-2.183-9-4.875c0-.124.009-.247.025-.368a8.284 8.284 0 0 0 1.897 1.384C6.809 15.914 9.315 16.5 12 16.5Z",
                "M12 20.25c2.685 0 5.19-.586 7.078-1.609a8.282 8.282 0 0 0 1.897-1.384c.016.121.025.244.025.368 0 2.692-4.03 4.875-9 4.875s-9-2.183-9-4.875c0-.124.009-.247.025-.368a8.284 8.284 0 0 0 1.897 1.384C6.809 19.664 9.315 20.25 12 20.25Z"
            )
        )
    }

    val Dataset: ImageVector get() = Storage

    val SystemUpdate: ImageVector by lazy {
        filledVector(
            name = "SystemUpdate",
            viewportWidth = 960f,
            viewportHeight = 960f,
            pathData = "M500,547L500,400L600,400L560,360L500,360L500,200Q500,183 511.5,171.5Q523,160 540,160L637,160Q648,160 657.5,165.5Q667,171 672,181L750,320L692,320L652,240L560,240L560,280L628,280L668,360L772,360L831,463Q805,452 777,446Q749,440 720,440Q653,440 595.5,469Q538,498 500,547ZM420,800L323,800Q312,800 302.5,794.5Q293,789 288,779L210,640L268,640L308,720L400,720L400,680L332,680L292,600L188,600L131,500Q129,495 127.5,490Q126,485 126,480Q126,476 131,460L188,360L292,360L332,280L400,280L400,240L308,240L268,320L210,320L288,181Q293,171 302.5,165.5Q312,160 323,160L420,160Q437,160 448.5,171.5Q460,183 460,200L460,360L400,360L360,400L460,400L460,520L372,520L332,440L240,440L200,480L308,480L348,560L460,560L460,616Q450,640 445,666Q440,692 440,720Q440,737 441.5,754Q443,771 448,788Q443,793 435.5,796.5Q428,800 420,800ZM720,840L840,720L812,692L740,764L740,600L700,600L700,764L628,692L600,720L720,840ZM578.5,861.5Q520,803 520,720Q520,637 578.5,578.5Q637,520 720,520Q803,520 861.5,578.5Q920,637 920,720Q920,803 861.5,861.5Q803,920 720,920Q637,920 578.5,861.5Z"
        )
    }

    val Info: ImageVector by lazy {
        strokedVector(
            name = "Info",
            paths = listOf(
                "M2.0,12.0 a10.0,10.0 0 1,0 20.0,0 a10.0,10.0 0 1,0 -20.0,0",
                "M12 16v-4",
                "M12 8h.01"
            )
        )
    }

    val Layers: ImageVector by lazy {
        strokedVector(
            name = "Layers",
            paths = listOf(
                "m12 2 10 6.5-10 6.5L2 8.5 12 2Z",
                "m2 15.5 10 6.5 10-6.5",
                "m2 12 10 6.5 10-6.5"
            )
        )
    }

    val BlurOn: ImageVector get() = Layers

    val Navigation: ImageVector by lazy {
        filledVector(
            name = "Navigation",
            evenOdd = true,
            pathData = "M3 10.5a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-3Zm3.75 1.5a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Zm6 0a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Z"
        )
    }

    val ViewDay: ImageVector by lazy {
        filledVector(
            name = "ViewDay",
            evenOdd = true,
            pathData = "M3 5.25a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 5.25v3a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 8.25v-3Zm0 8.25a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 13.5v5.25A2.25 2.25 0 0 1 18.75 21H5.25A2.25 2.25 0 0 1 3 18.75V13.5Z"
        )
    }

    val HighQuality: ImageVector by lazy {
        Sliders
    }

    val AutoMode: ImageVector by lazy {
        strokedVector(
            name = "AutoMode",
            paths = listOf(
                "M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4Zm0 0c2 2.67 4 4 6 4a4 4 0 0 0 0-8c-2 0-4 1.33-6 4Z"
            )
        )
    }

    val LightMode: ImageVector by lazy {
        filledVector(
            name = "LightMode",
            pathData = "M12 2.25a.75.75 0 0 1 .75.75v2.25a.75.75 0 0 1-1.5 0V3a.75.75 0 0 1 .75-.75ZM7.5 12a4.5 4.5 0 1 1 9 0 4.5 4.5 0 0 1-9 0ZM18.894 6.166a.75.75 0 0 0-1.06-1.06l-1.591 1.59a.75.75 0 1 0 1.06 1.061l1.591-1.59ZM21.75 12a.75.75 0 0 1-.75.75h-2.25a.75.75 0 0 1 0-1.5H21a.75.75 0 0 1 .75.75ZM17.834 18.894a.75.75 0 0 0 1.06-1.06l-1.59-1.591a.75.75 0 1 0-1.061 1.06l1.59 1.591ZM12 18a.75.75 0 0 1 .75.75V21a.75.75 0 0 1-1.5 0v-2.25A.75.75 0 0 1 12 18ZM7.758 17.303a.75.75 0 0 0-1.061-1.06l-1.591 1.59a.75.75 0 0 0 1.06 1.061l1.591-1.59ZM6 12a.75.75 0 0 1-.75.75H3a.75.75 0 0 1 0-1.5h2.25A.75.75 0 0 1 6 12ZM6.697 7.757a.75.75 0 0 0 1.06-1.06l-1.59-1.591a.75.75 0 0 0-1.061 1.06l1.59 1.591Z"
        )
    }

    val Lightbulb: ImageVector by lazy {
        filledVector(
            name = "Lightbulb",
            pathData = "M12 .75a8.25 8.25 0 0 0-4.135 15.39c.686.398 1.115 1.008 1.134 1.623a.75.75 0 0 0 .577.706c.352.083.71.148 1.074.195.323.041.6-.218.6-.544v-4.661a6.714 6.714 0 0 1-.937-.171.75.75 0 1 1 .374-1.453 5.261 5.261 0 0 0 2.626 0 .75.75 0 1 1 .374 1.452 6.712 6.712 0 0 1-.937.172v4.66c0 .327.277.586.6.545.364-.047.722-.112 1.074-.195a.75.75 0 0 0 .577-.706c.02-.615.448-1.225 1.134-1.623A8.25 8.25 0 0 0 12 .75ZM8.625 19.5a.75.75 0 0 1 .75-.75h5.25a.75.75 0 0 1 0 1.5h-5.25a.75.75 0 0 1-.75-.75ZM9.75 21.75a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Z"
        )
    }

    val Download: ImageVector by lazy {
        filledVector(
            name = "Download",
            evenOdd = true,
            pathData = "M12 2.25a.75.75 0 0 1 .75.75v11.69l3.22-3.22a.75.75 0 1 1 1.06 1.06l-4.5 4.5a.75.75 0 0 1-1.06 0l-4.5-4.5a.75.75 0 1 1 1.06-1.06l3.22 3.22V3a.75.75 0 0 1 .75-.75Zm-9 13.5a.75.75 0 0 1 .75.75v2.25a1.5 1.5 0 0 0 1.5 1.5h13.5a1.5 1.5 0 0 0 1.5-1.5V16.5a.75.75 0 0 1 1.5 0v2.25a3 3 0 0 1-3 3H5.25a3 3 0 0 1-3-3V16.5a.75.75 0 0 1 .75-.75Z"
        )
    }

    val FileDownload: ImageVector get() = Download

    val UploadFile: ImageVector by lazy {
        filledVector(
            name = "UploadFile",
            evenOdd = true,
            pathData = "M10.5 3.75a6 6 0 0 0-5.98 6.496A5.25 5.25 0 0 0 6.75 20.25H18a4.5 4.5 0 0 0 2.206-8.423 3.75 3.75 0 0 0-4.133-4.303A6.001 6.001 0 0 0 10.5 3.75Zm2.03 5.47a.75.75 0 0 0-1.06 0l-3 3a.75.75 0 1 0 1.06 1.06l1.72-1.72v4.94a.75.75 0 0 0 1.5 0v-4.94l1.72 1.72a.75.75 0 1 0 1.06-1.06l-3-3Z"
        )
    }

    val Image: ImageVector by lazy {
        filledVector(
            name = "Image",
            evenOdd = true,
            pathData = "M1.5 6a2.25 2.25 0 0 1 2.25-2.25h16.5A2.25 2.25 0 0 1 22.5 6v12a2.25 2.25 0 0 1-2.25 2.25H3.75A2.25 2.25 0 0 1 1.5 18V6ZM3 16.06V18c0 .414.336.75.75.75h16.5A.75.75 0 0 0 21 18v-1.94l-2.69-2.689a1.5 1.5 0 0 0-2.12 0l-.88.879.97.97a.75.75 0 1 1-1.06 1.06l-5.16-5.159a1.5 1.5 0 0 0-2.12 0L3 16.061Zm10.125-7.81a1.125 1.125 0 1 1 2.25 0 1.125 1.125 0 0 1-2.25 0Z"
        )
    }

    val OpenInNew: ImageVector by lazy {
        strokedVector(
            name = "OpenInNew",
            paths = listOf(
                "M15 3h6v6",
                "M10 14 21 3",
                "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"
            )
        )
    }

    val CalendarToday: ImageVector by lazy {
        strokedVector(
            name = "CalendarToday",
            paths = listOf(
                "M8 2v4",
                "M16 2v4",
                "M3 10h18",
                "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z"
            )
        )
    }

    val PhoneAndroid: ImageVector by lazy {
        strokedVector(
            name = "PhoneAndroid",
            paths = listOf(
                "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z",
                "M12 18h.01"
            )
        )
    }

    val Code: ImageVector by lazy {
        strokedVector(
            name = "Code",
            paths = listOf(
                "m16 18 6-6-6-6",
                "m8 6-6 6 6 6"
            )
        )
    }

    val Speed: ImageVector by lazy {
        strokedVector(
            name = "Speed",
            paths = listOf(
                "m12 14 4-4",
                "M3.34 19a10 10 0 1 1 17.32 0"
            )
        )
    }

    val Tune: ImageVector get() = Sliders
    val PlayArrow: ImageVector get() = Play

    val Whatshot: ImageVector by lazy {
        filledVector(
            name = "Whatshot",
            evenOdd = true,
            pathData = "M12.963 2.286a.75.75 0 0 0-1.071-.136 9.742 9.742 0 0 0-3.539 6.176 7.547 7.547 0 0 1-1.705-1.715.75.75 0 0 0-1.152-.082A9 9 0 1 0 15.68 4.534a7.46 7.46 0 0 1-2.717-2.248ZM15.75 14.25a3.75 3.75 0 1 1-7.313-1.172c.628.465 1.35.81 2.133 1a5.99 5.99 0 0 1 1.925-3.546 3.75 3.75 0 0 1 3.255 3.718Z"
        )
    }
}
