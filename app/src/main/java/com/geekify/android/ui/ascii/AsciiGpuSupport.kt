package com.geekify.android.ui.ascii

import android.os.Build

/**
 * Hardware capability detector and fail-safe switch for GPU AGSL ASCII rendering.
 *
 * If shader compilation or rendering fails at runtime on any device, the GPU path
 * is permanently disabled and the renderer falls back to the adaptive CPU blit path.
 */
object AsciiGpuSupport {
    @Volatile
    var isGpuDisabled: Boolean = false
        private set

    @Volatile
    var disableReason: String? = null
        private set

    fun isSupported(): Boolean {
        return !isGpuDisabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun disableGpu(reason: Throwable) {
        isGpuDisabled = true
        disableReason = reason.message ?: reason.javaClass.simpleName
    }

    /**
     * Resets the GPU disable flag (useful for testing).
     */
    fun resetForTesting() {
        isGpuDisabled = false
        disableReason = null
    }
}
