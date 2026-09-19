/*
 * COMPILE-TIME STUBS ONLY. Not part of the app.
 *
 * These reproduce the handful of AndroidX Kotlin *extension* APIs the app uses.
 * Extension functions cannot be expressed in Java, so they live here; every
 * class-type stub lives in ../java and is compiled with javac first.
 *
 * They exist so that the real app sources can be type-checked on a machine with
 * no Android SDK. Every body is TODO() - nothing here is executed.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "NOTHING_TO_INLINE")

package androidx.lifecycle

import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope

/** Stub for androidx.lifecycle:lifecycle-runtime-ktx. */
abstract class LifecycleCoroutineScope : CoroutineScope

val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope
    get() = TODO("stub")

suspend fun LifecycleCoroutineScope.repeatOnLifecycle(
    state: Lifecycle.State,
    block: suspend CoroutineScope.() -> Unit,
) {
    TODO("stub")
}

suspend fun LifecycleOwner.repeatOnLifecycle(
    state: Lifecycle.State,
    block: suspend CoroutineScope.() -> Unit,
) {
    TODO("stub")
}

/** Stub for androidx.lifecycle:lifecycle-viewmodel-ktx. */
val ViewModel.viewModelScope: CoroutineScope
    get() = TODO("stub")
