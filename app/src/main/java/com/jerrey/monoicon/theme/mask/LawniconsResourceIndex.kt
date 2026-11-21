package com.jerrey.monoicon.theme.mask

/** Immutable metadata shared by list filtering and bitmap lookup; no image decoding. */
internal class LawniconsResourceIndex(
    index: Map<String, String>,
    aliases: Map<String, String>,
    availableAssets: Set<String>? = null,
) {
    val aliases: Map<String, String> = normalizedEntries(aliases, availableAssets)
    val index: Map<String, String> = normalizedEntries(index, availableAssets).let { entries ->
        // A package-only identity must work for packages represented solely by components.
        // Explicit package entries win; sorting makes the fallback stable across map orders.
        val result = entries.toMutableMap()
        entries.toSortedMap().forEach { (identity, asset) ->
            result.putIfAbsent(identity.substringBefore('/'), asset)
        }
        this.aliases.toSortedMap().forEach { (identity, asset) ->
            result.putIfAbsent(identity.substringBefore('/'), asset)
        }
        result.toMap()
    }
    val packages: Set<String> = (this.index.keys + this.aliases.keys)
        .mapTo(linkedSetOf()) { it.substringBefore('/') }

    fun resolve(identity: String?): String? = resolve(identity, index, aliases)

    companion object {
        fun resolve(identity: String?, index: Map<String, String>, aliases: Map<String, String>): String? {
            val id = normalizeIdentity(identity) ?: return null
            val pkg = id.substringBefore('/')
            return aliases[id] ?: aliases[pkg] ?: index[id] ?: index[pkg]
        }

        private fun normalizedEntries(entries: Map<String, String>, available: Set<String>?): Map<String, String> =
            buildMap {
                entries.forEach { (identity, asset) ->
                    val key = normalizeIdentity(identity)
                    val name = asset.trim()
                    if (key != null && name.isNotEmpty() && (available == null || name in available)) put(key, name)
                }
            }

        private fun normalizeIdentity(identity: String?): String? {
            val id = identity?.trim().orEmpty()
            val pkg = id.substringBefore('/')
            if (pkg.isEmpty() || pkg == "unknown") return null
            if ('/' !in id) return pkg
            val cls = id.substringAfter('/').trim()
            if (cls.isEmpty()) return pkg
            val expanded = when {
                cls.startsWith('.') -> pkg + cls
                '.' !in cls -> "$pkg.$cls"
                else -> cls
            }
            return "$pkg/$expanded"
        }
    }
}
