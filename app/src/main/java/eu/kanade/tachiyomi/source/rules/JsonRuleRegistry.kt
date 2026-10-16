package eu.kanade.tachiyomi.source.rules

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    private val subscriptionState = MutableStateFlow(
        prefs.getStringSet("subscriptions", emptySet()).orEmpty().sorted(),
    )
    val subscriptions = subscriptionState.asStateFlow()

    private fun read(): List<JSONObject> = runCatching {
        val json = JSONArray(prefs.getString("rules", "[]") ?: "[]")
        (0 until json.length()).map { json.getJSONObject(it) }
    }.getOrDefault(emptyList())

    @Synchronized
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
        // A manually imported source should immediately be visible in Browse / Sources.
        // Newly imported languages otherwise remain hidden by the global language filter.
        val sourcePreferences = Injekt.get<SourcePreferences>()
        val currentLanguages = sourcePreferences.enabledLanguages.get()
        val importedLanguages = validated.map {
            it.optString("bookSourceLanguage").ifBlank { "zh" }
        }.toSet()
        if (!currentLanguages.containsAll(importedLanguages)) {
            sourcePreferences.enabledLanguages.set(currentLanguages + importedLanguages)
        }
        state.value = current.values.toList()
        return validated.size
    }

    suspend fun importSubscription(address: String): Int {
        val uri = android.net.Uri.parse(address.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Subscriptions must use HTTPS"
        }
        val content = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(address.trim()).get().build()
            Injekt.get<NetworkHelper>().client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Subscription HTTP ${response.code}" }
                val reader = response.body?.charStream() ?: error("Empty subscription")
                reader.use {
                    val buffer = CharArray(8192)
                    val output = StringBuilder()
                    while (true) {
                        val size = it.read(buffer)
                        if (size == -1) break
                        output.append(buffer, 0, size)
                        require(output.length <= 2_000_000) { "Subscription exceeds 2 MB" }
                    }
                    output.toString()
                }
            }
        }
        val count = importJson(content)
        synchronized(this) {
            val urls = (subscriptionState.value + address.trim()).distinct().sorted()
            check(prefs.edit().putStringSet("subscriptions", urls.toSet()).commit())
            subscriptionState.value = urls
        }
        return count
    }

    suspend fun refreshSubscriptions(): Map<String, Result<Int>> {
        val result = linkedMapOf<String, Result<Int>>()
        for (subscription in subscriptionState.value) {
            result[subscription] = runCatching { importSubscription(subscription) }
        }
        return result
    }

    @Synchronized
    fun removeSubscription(address: String) {
        val urls = subscriptionState.value.filterNot { it == address.trim() }
        check(prefs.edit().putStringSet("subscriptions", urls.toSet()).commit())
        subscriptionState.value = urls
    }

    private fun key(rule: JSONObject): String =
        rule.optString("bookSourceUrl").ifBlank { rule.optString("baseUrl") }.trimEnd('/') +
            "|" + rule.optString("bookSourceName").ifBlank { rule.optString("name") } +
            "|" + rule.optString("bookSourceLanguage").ifBlank { "zh" }

    @Synchronized
    fun remove(url: String, name: String, lang: String) {
        val entries = read().filterNot { key(it) == url.trimEnd('/') + "|" + name + "|" + lang }
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        check(prefs.edit().putString("rules", arr.toString()).commit())
        state.value = entries
    }
}
