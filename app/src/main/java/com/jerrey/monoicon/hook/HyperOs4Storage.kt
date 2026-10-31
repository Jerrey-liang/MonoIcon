package com.jerrey.monoicon.hook

/** The launcher can read a published generation during Direct Boot. */
internal object HyperOs4Storage {
    fun directory(uid: Int): String = "/data/user_de/${uid / 100_000}/com.miui.home/files"
    fun legacyDirectory(uid: Int): String = "/data/user/${uid / 100_000}/com.miui.home/files"
}
