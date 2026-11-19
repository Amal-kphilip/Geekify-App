package com.geekify.android.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.dsl.module

val IoDispatcherQualifier = named("io")
val DefaultDispatcherQualifier = named("default")
val MainDispatcherQualifier = named("main")

/** Dispatchers are injected via Koin qualifiers so repositories can be tested and never block the main thread. */
val dispatchersModule = module {
    single<CoroutineDispatcher>(IoDispatcherQualifier) { Dispatchers.IO }
    single<CoroutineDispatcher>(DefaultDispatcherQualifier) { Dispatchers.Default }
    single<CoroutineDispatcher>(MainDispatcherQualifier) { Dispatchers.Main }
}
