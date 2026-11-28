package com.jerrey.monoicon.safety

import androidx.annotation.Keep

/** Persistent startup guard for the Java launcher hooks. */
@Keep
internal object LauncherSafetyNative {
    private val loaded = runCatching { System.loadLibrary("monoicon_launcher_safety") }.isSuccess

    /** Storage/JNI failures disable hooks too: protection must be armed first. */
    fun begin(): Boolean = loaded && runCatching { nativeBegin() }.getOrDefault(false)

    private external fun nativeBegin(): Boolean
}
