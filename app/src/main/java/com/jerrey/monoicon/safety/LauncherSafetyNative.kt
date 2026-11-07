package com.jerrey.monoicon.safety

import androidx.annotation.Keep

/** One persistent guard implementation for both Java and native launchers. */
@Keep
internal object LauncherSafetyNative {
    private val loaded = runCatching { System.loadLibrary("monoicon_hyperos4") }.isSuccess

    /** Storage/JNI failures disable hooks too: protection must be armed first. */
    fun begin(): Boolean = loaded && runCatching { nativeBegin() }.getOrDefault(false)
    fun isBlocked(): Boolean = !loaded || runCatching { nativeIsBlocked() }.getOrDefault(true)

    private external fun nativeBegin(): Boolean
    private external fun nativeIsBlocked(): Boolean
}
