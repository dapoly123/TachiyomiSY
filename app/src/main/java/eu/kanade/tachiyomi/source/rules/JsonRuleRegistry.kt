package eu.kanade.tachiyomi.source.rules

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-owned, declarative source definitions. No APK, reflection or rule JavaScript is executed.
 * A rule is loaded from persistent storage when the application starts.
 */
class JsonRuleRegistry private constructor(context: Context) {
    companion object {
        @Volatile private var instance: JsonRuleRegistry? = null
        fun get(context: Context): JsonRuleRegistry = instance ?: synchronized(this) {
            instance ?: JsonRuleRegistry(context.applicationContext).also { instance = it }
        }
    }
    private val prefs = context.applicationContext.getSharedPreferences("json_rule_sources_v1", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val rules = state.asStateFlow()

    private fun read(): List<JSONObject> = runCatching {
        val json = JSONArray(prefs.getString("rules", "[]") ?: "[]")
        (0 until json.length()).map { json.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun importJson(text: String): Int {
        require(text.length <= 2_000_000) { "JSON file too large (2 MB maximum)" }
        val trimmed = text.trim()
        val root = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val obj = JSONObject(trimmed)
            when {
                obj.optJSONArray("sources") != null -> obj.getJSONArray("sources")
                obj.optJSONArray("rules") != null -> obj.getJSONArray("rules")
                else -> JSONArray().put(obj)
            }
        }
        require(root.length() <= 200) { "Maximum 200 rules per import" }
        val validated = (0 until root.length()).map { index ->
            val obj = root.getJSONObject(index)
            val name = obj.optString("bookSourceName").ifBlank { obj.optString("name") }
            val url = obj.optString("bookSourceUrl").ifBlank { obj.optString("baseUrl") }
            require(name.isNotBlank() && name.length <= 120) { "Invalid source name at index $index" }
            require(url.startsWith("https://") || url.startsWith("http://")) { "Invalid source URL for $name" }
            require(android.net.Uri.parse(url).host != null) { "Invalid source host for $name" }
            obj
        }
        val current = read().associateByTo(linkedMapOf()) { key(it) }
        validated.forEach { current[key(it)] = it }
        require(current.size <= 500) { "Maximum 500 installed rules" }
        val arr = JSONArray()
        current.values.forEach { arr.put(it) }
        check(prefs.edit().putString("rules", arr.toString()).commit()) { "Failed to persist rules" }
        state.value = current.values.toList()
        return validated.size
    }

    private fun key(rule: JSONObject): String =
        rule.optString("bookSourceUrl").ifBlank { rule.optString("baseUrl") }.trimEnd('/')

    fun remove(url: String) {
        val entries = read().filterNot { key(it) == url.trimEnd('/') }
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        check(prefs.edit().putString("rules", arr.toString()).commit())
        state.value = entries
    }
}
