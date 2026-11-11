package com.geekify.android.di

import com.geekify.android.player.PlaybackDiagnosticsTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsEntryPoint {
    fun diagnosticsTracker(): PlaybackDiagnosticsTracker
}
