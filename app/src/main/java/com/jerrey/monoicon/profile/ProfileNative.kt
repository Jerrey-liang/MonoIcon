package com.jerrey.monoicon.profile

import androidx.annotation.Keep

@Keep
internal object ProfileNative {
    init { System.loadLibrary("monoicon_hyperos4") }
    /** Empty means valid; the same parser runs again inside native_init. */
    external fun validate(source: ByteArray): String
    external fun systemProperty(name: String): String
}
