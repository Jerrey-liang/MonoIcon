package com.jerrey.monoicon.hook

/** Versioned scalar contract with the native payload; all fields commit together. */
internal data class HyperOs4Config(
    val enabled: Boolean,
    val background: Int,
    val foreground: Int,
    val circle: Boolean,
    val lawnicons: Boolean,
) {
    fun encode(): String = "MONOICON 1 ${bit(enabled)} ${argb(background)} ${argb(foreground)} ${bit(circle)} ${bit(lawnicons)}\n"

    /** A unique generation also makes native acknowledgement unambiguous. */
    fun encode(generation: String): String {
        require(Regex("g-[a-f0-9]{32}").matches(generation))
        return "MONOICON 2 ${bit(enabled)} ${argb(background)} ${argb(foreground)} ${bit(circle)} ${bit(lawnicons)} $generation\n"
    }

    companion object {
        fun supports(versionCode: Long, versionName: String?, hasCode: Boolean, arm64: Boolean): Boolean =
            versionCode == 801026144L && versionName == "RELEASE-8.01.02.6144-260818-08251107-R" && !hasCode && arm64

        private fun bit(value: Boolean) = if (value) 1 else 0
        private fun argb(value: Int) = "0x" + value.toUInt().toString(16).padStart(8, '0')
    }
}
