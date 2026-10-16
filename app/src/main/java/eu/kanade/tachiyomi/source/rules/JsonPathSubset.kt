package eu.kanade.tachiyomi.source.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe, deliberately narrow JSONPath subset for declarative PipiMiao sources.
 *
 * Supports $.foo.bar, $.[*], $.items[*], $.items[0], $.* and alternatives
 * separated by ||. No filters, script expressions, or recursive descent.
 */
internal object JsonPathSubset {
    private val token = Regex("""(?:^|\.)\$|\.([\w-]+)|\[(\d+|\*)]|^\$|^([\w-]+)|^\*""")

    fun decode(body: String): Any {
        val trimmed = body.trim()
        require(trimmed.length <= 2_000_000) { "JSON source response exceeds 2 MB" }
        return if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
    }

    fun select(root: Any, expression: String): List<Any> {
        val rule = expression.trim().removePrefix("-").trim()
        require(!rule.contains("@js:", ignoreCase = true) && !rule.contains("<js>", ignoreCase = true)) {
            "JavaScript transforms in JSONPath are not supported"
        }
        for (alternative in rule.split("||")) {
            val path = alternative.trim().replace("[*]data", "[*].data").replace(".[*]", "[*]")
            val match = Regex("""(?:\$)(.*)""").matchEntire(path)
            require(match != null) { "Unsupported JSONPath: $path" }
            val suffix = match.groupValues[1]
            val tokens = mutableListOf<String>()
            var pos = 0
            val scanner = Regex("""(?:\.([\w-]+|\*)|\[(\d+|\*)])""")
            while (pos < suffix.length) {
                val m = scanner.find(suffix, pos)
                require(m != null && m.range.first == pos) { "Unsupported JSONPath: $path" }
                tokens += m.groupValues[1].ifBlank { m.groupValues[2] }
                pos = m.range.last + 1
            }
            var values: List<Any> = listOf(root)
            for (step in tokens) {
                values = values.flatMap { node ->
                    when {
                        step == "*" && node is JSONArray ->
                            (0 until node.length()).mapNotNull { i -> node.opt(i) }
                        step == "*" && node is JSONObject ->
                            node.keys().asSequence().mapNotNull { node.opt(it) }.toList()
                        node is JSONObject -> listOfNotNull(node.opt(step))
                        node is JSONArray -> step.toIntOrNull()
                            ?.takeIf { it in 0 until node.length() }
                            ?.let { listOfNotNull(node.opt(it)) }.orEmpty()
                        else -> emptyList()
                    }
                }
            }
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    fun text(node: Any, expression: String): String {
        if (expression.isBlank()) return ""
        val clean = expression.substringBefore("@put:").trim()
        require(!clean.contains("@js:", ignoreCase = true)) { "JavaScript transforms are not supported" }
        if (clean.startsWith("$") || clean.startsWith("-$") || clean.startsWith(".")) {
            return select(node, if (clean.startsWith(".")) "$" + clean else clean).firstOrNull()?.toString().orEmpty()
        }
        return interpolate(node, clean)
    }

    fun interpolate(node: Any, template: String): String {
        val matcher = Regex("""\{\{?\$\.([\w.-]+)\}\}?""")
        return matcher.replace(template) {
            val path = "$." + it.groupValues[1]
            select(node, path).firstOrNull()?.toString().orEmpty()
        }
    }
}
