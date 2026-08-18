package com.kobe.camscanner.core.common

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.RUNTIME

/**
 * Image work is CPU-bound and must never run on the main thread (SDS 45). Injecting the dispatchers
 * rather than reaching for Dispatchers.Default also makes the pure logic testable.
 */
@Qualifier @Retention(RUNTIME) annotation class IoDispatcher
@Qualifier @Retention(RUNTIME) annotation class DefaultDispatcher
@Qualifier @Retention(RUNTIME) annotation class ApplicationScope
