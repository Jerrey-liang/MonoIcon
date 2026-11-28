package com.jerrey.monoicon.theme.archive

/** For su versions without -M, only operate after the shell proves it already runs in init's
 * global mount namespace. A private mount view can expose a different theme or backup. */
internal object ThemeRootMount {
    val guard = """
        this_mnt=$(/system/bin/readlink /proc/self/ns/mnt) &&
        init_mnt=$(/system/bin/readlink /proc/1/ns/mnt) &&
        [ -n "${'$'}this_mnt" ] && [ "${'$'}this_mnt" = "${'$'}init_mnt" ] || {
            echo 'Cannot verify the global mount namespace'
            exit 69
        }
    """.trimIndent()

    private val optionError = Regex(
        """(?i)\b(?:unrecognized|unknown|illegal|invalid|unsupported)\s+(?:option|argument)\b.{0,45}(?:mount-master|(?<!\w)M(?!\w))"""
    )

    fun unsupportedOption(exitCode: Int, output: String, entered: String): Boolean =
        exitCode != 0 && output.lineSequence().none { it == entered } &&
            output.lineSequence().any { optionError.containsMatchIn(it) }
}
