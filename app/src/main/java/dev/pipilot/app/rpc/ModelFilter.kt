package dev.pipilot.app.rpc

/**
 * Applies pi's enabledModels patterns to the RPC model snapshot.
 * Patterns support provider/model, bare model ids, simple glob characters,
 * and the optional :thinking-level suffix used by pi's TUI.
 */
fun filterEnabledModels(models: List<PiModel>, patterns: List<String>?): List<PiModel> {
    if (patterns.isNullOrEmpty()) return models
    val out = ArrayList<PiModel>()
    for (raw in patterns) {
        val pattern = raw.trim()
        if (pattern.isEmpty()) continue
        val base = pattern.substringBeforeLast(':').takeIf { pattern.lastIndexOf(':') > 0 &&
            VALID_THINKING_LEVELS.contains(pattern.substringAfterLast(':').lowercase()) } ?: pattern
        val matches = if (base.any { it == '*' || it == '?' || it == '[' }) {
            models.filter { globMatches(base, "${it.provider}/${it.id}") || globMatches(base, it.id) }
        } else {
            val exact = models.filter {
                "${it.provider}/${it.id}".equals(base, ignoreCase = true) || it.id.equals(base, ignoreCase = true)
            }
            if (exact.isNotEmpty()) exact else {
                val partial = models.filter {
                    it.id.contains(base, ignoreCase = true) || it.name?.contains(base, ignoreCase = true) == true
                }
                if (partial.isEmpty()) emptyList() else {
                    val aliases = partial.filter { !it.id.matches(Regex(".*-\\d{8}$")) }
                    (aliases.ifEmpty { partial }).sortedByDescending { it.id }
                        .take(1)
                }
            }
        }
        for (model in matches) {
            if (out.none { it.provider.equals(model.provider, true) && it.id.equals(model.id, true) }) out += model
        }
    }
    return out
}

private val VALID_THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

private fun globMatches(pattern: String, value: String): Boolean {
    val regex = buildString {
        append('^')
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> append(".*")
                '?' -> append('.')
                '[' -> {
                    val end = pattern.indexOf(']', i + 1)
                    if (end > i + 1) {
                        append(pattern.substring(i, end + 1))
                        i = end
                    } else append("\\[")
                }
                else -> append(Regex.escape(c.toString()))
            }
            i++
        }
        append('$')
    }
    return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(value)
}
