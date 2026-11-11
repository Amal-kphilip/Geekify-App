package com.geekify.android.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class GeekifyFeatureFlags(
    val mlRecommendationsEnabled: Boolean = true,
    val intelligentCrossfadeEnabled: Boolean = true,
    val smartMixesEnabled: Boolean = true,
    val androidAutoBrowseEnabled: Boolean = true,
    val playbackDiagnosticsEnabled: Boolean = true
)

@Singleton
class FeatureFlagManager @Inject constructor() {
    private val _flags = MutableStateFlow(GeekifyFeatureFlags())
    val flags: StateFlow<GeekifyFeatureFlags> = _flags.asStateFlow()

    fun updateFlags(transform: (GeekifyFeatureFlags) -> GeekifyFeatureFlags) {
        _flags.value = transform(_flags.value)
    }

    fun isMlRecommendationsEnabled(): Boolean = _flags.value.mlRecommendationsEnabled
    fun isCrossfadeEnabled(): Boolean = _flags.value.intelligentCrossfadeEnabled
    fun isSmartMixesEnabled(): Boolean = _flags.value.smartMixesEnabled
    fun isAndroidAutoBrowseEnabled(): Boolean = _flags.value.androidAutoBrowseEnabled
}
