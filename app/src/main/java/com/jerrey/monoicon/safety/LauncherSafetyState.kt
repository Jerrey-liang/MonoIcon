package com.jerrey.monoicon.safety

/** The persistent native guard owns this format. Malformed existing records fail closed. */
internal data class LauncherSafetyState(val blocked: Boolean, val resetToken: String, val processId: Int) {
    companion object {
        fun parse(text: String): LauncherSafetyState? {
            val fields = text.trim().split(Regex("\\s+"))
            if (fields.size != 9 || fields[0] != "MONOICON_SAFE" || fields[1] != "1" ||
                fields[2] !in setOf("0", "1") || !Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(fields[3]) ||
                (fields[4].toLongOrNull() ?: 0) !in 1..Int.MAX_VALUE.toLong() || (fields[5].toLongOrNull() ?: 0) <= 0 ||
                (fields[6].toLongOrNull() ?: -1) < 0 || fields[7] !in setOf("0", "1") ||
                !validToken(fields[8])) return null
            return LauncherSafetyState(fields[2] == "1", fields[8], fields[4].toInt())
        }

        fun validToken(token: String): Boolean = token == "-" || Regex("[0-9a-f]{32}").matches(token)

        fun isBlocked(state: String?, reset: String): Boolean {
            if (!validToken(reset)) return true
            if (state == null) return false
            val parsed = parse(state) ?: return true
            // A user retry is pending until the next launcher process consumes it.
            return parsed.blocked && (reset == "-" || parsed.resetToken == reset)
        }
    }
}
