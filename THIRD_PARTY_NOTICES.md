# Third-party source and dependency notices

## Metrolist InnerTube backend and playback extraction integration

The `innertube/` module and the adapted Android PO-token integration under
`app/src/main/java/com/geekify/android/player/potoken/` are derived from the
Metrolist project (`https://github.com/MetrolistGroup/Metrolist`). The source is
licensed under GNU GPL version 3. The Geekify repository's GPL-3.0 license remains
applicable to this combined work. Original upstream history and notices should be
preserved when redistributing modified source.

The `innertube` module depends on the separately published InnerTubeX library
`com.github.MetrolistGroup.innertubex:innertubex:v0.8.4`. Its upstream publication metadata
also declares GNU GPL version 3. See `https://github.com/MetrolistGroup/innertubex` for
source, release details and contributor notices.

The local Geekify adapter class names and the vendored InnerTube package namespace were renamed for project organization. This does not change the upstream origin or license of derived code; this notice and the bundled GPL-3.0 license are intentionally retained.
