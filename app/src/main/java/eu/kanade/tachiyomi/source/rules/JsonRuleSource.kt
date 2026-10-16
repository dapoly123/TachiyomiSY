package eu.kanade.tachiyomi.source.rules

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A data-only rule adapter: supports HTML/CSS selectors without executing remote scripts.
 * Unsupported PipiMiao expressions fail loudly instead of silently returning wrong pages.
 */
class JsonRuleSource(private val rule: JSONObject) : HttpSource() {
    override val name: String = rule.optString("bookSourceName").ifBlank { rule.optString("name") }
    override val lang: String = rule.optString("bookSourceLanguage").ifBlank { "zh" }
    override val baseUrl: String =
        rule.optString("bookSourceUrl").ifBlank { rule.optString("baseUrl") }.trimEnd('/')
    override val supportsLatest = false
    override val id: Long = run {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("tachiyomi-json-rule:" + baseUrl + ":" + name + ":" + lang).toByteArray())
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (digest[i].toLong() and 255L)
        result or Long.MIN_VALUE
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        val text = rule.optString("bookSourceHeader")
        if (text.startsWith("{")) {
            val obj = JSONObject(text)
            obj.keys().forEach { key ->
                if (!key.equals("Host", ignoreCase = true)) {
                    set(key, obj.optString(key))
                }
            }
        }
    }

    private fun field(name: String): String = rule.optString(name).trim()

    private fun resolveUrl(value: String, relativeTo: String = baseUrl): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Missing URL" }
        val target = URI(relativeTo).resolve(trimmed.replace(" ", "%20"))
        require(target.scheme == "https" || target.scheme == "http") { "Unsupported URL scheme" }
        return target.toString()
    }

    override fun getMangaUrl(manga: SManga): String = resolveUrl(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = resolveUrl(chapter.url)

    private suspend fun fetch(address: String): Pair<String, String> = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(resolveUrl(address)).headers(headers).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}: ${response.request.url}" }
            val body = response.body?.string() ?: error("Empty document response")
            body to response.request.url.toString()
        }
    }

    private suspend fun document(address: String): Document {
        val (body, finalUrl) = fetch(address)
        return Jsoup.parse(body, finalUrl)
    }

    private val attributes = setOf("text", "html", "href", "src", "style", "title", "data-src", "data-original", "srcset")

    /** Supports plain CSS plus PipiMiao's class.foo@tag.ul@tag.li shorthand. */
    private fun selectorAndAttribute(expression: String): Pair<String, String> {
        require(!expression.contains("@js:", ignoreCase = true) && !expression.contains("<js>", ignoreCase = true)) {
            "JavaScript rules are unsupported"
        }
        require(!expression.startsWith("$") && !expression.startsWith("-$")) {
            "JSONPath rules must use JSON mode"
        }
        val pieces = expression.substringBefore("##").trim().split('@')
        val attribute = pieces.last().takeIf { it in attributes } ?: "text"
        val steps = if (pieces.last() == attribute && pieces.size > 1) pieces.dropLast(1) else pieces
        val selector = steps.joinToString(" ") { step ->
            val t = step.trim()
            when {
                t.startsWith("tag.") -> t.removePrefix("tag.").replace(Regex("\\.(\\d+)$")) { match ->
                    ":eq(${match.groupValues[1]})"
                }
                t.startsWith("class.") -> "." + t.removePrefix("class.")
                t in attributes -> ""
                else -> t
            }
        }.trim()
        return selector to attribute
    }

    private fun select(root: Element, expression: String): List<Element> {
        for (alternative in expression.split("||")) {
            val (selector, _) = selectorAndAttribute(alternative)
            val found = if (selector.isBlank()) listOf(root) else root.select(selector)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun value(root: Element, expression: String): String {
        if (expression.isBlank()) return ""
        for (alternative in expression.split("||")) {
            val (selector, attr) = selectorAndAttribute(alternative)
            val node = (if (selector.isBlank()) listOf(root) else root.select(selector)).firstOrNull() ?: continue
            val output = when (attr) {
                "text" -> node.text()
                "html" -> node.html()
                "href", "src" -> node.absUrl(attr).ifBlank { node.attr(attr) }
                else -> node.attr(attr)
            }.trim()
            if (output.isNotBlank()) return output
        }
        return ""
    }

    private fun photoUrl(node: Element, attribute: String): String {
        val attr = attribute.ifBlank { "src" }
        val raw = if (attr == "text") node.text() else node.attr(attr)
        val fallback = raw.ifBlank {
            listOf("data-src", "data-original", "data-lazy-src", "src")
                .firstNotNullOfOrNull { key -> node.attr(key).takeIf { it.isNotBlank() } }.orEmpty()
        }
        val actual = if (attr == "srcset") fallback.substringBefore(",").trim().substringBefore(" ") else fallback
        return actual.takeIf { it.isNotBlank() }?.let { resolveUrl(it, node.baseUri()) }.orEmpty()
    }

    private fun list(doc: Document, prefix: String): List<SManga> {
        val listRule = field(prefix + "List")
        if (listRule.isBlank()) return emptyList()
        return select(doc, listRule).mapNotNull { element ->
            val name = value(element, field(prefix + "Name").ifBlank { "a@text" })
            val link = value(element, field(prefix + "NoteUrl").ifBlank { "a@href" })
            if (name.isBlank() || link.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = name
                this.url = resolveUrl(link, doc.location())
                thumbnail_url = value(element, field(prefix + "CoverUrl"))
                    .takeIf { it.isNotBlank() }?.let { resolveUrl(it, doc.location()) }
            }
        }
    }

    private fun jsonList(body: String, sourceUrl: String, prefix: String): List<SManga> {
        val listRule = field(prefix + "List")
        val root = JsonPathSubset.decode(body)
        val records = flattenJson(JsonPathSubset.select(root, listRule))
        return records.mapNotNull { item ->
            val title = JsonPathSubset.text(item, field(prefix + "Name"))
            val link = JsonPathSubset.text(item, field(prefix + "NoteUrl"))
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                this.url = resolveUrl(link, sourceUrl)
                val coverRule = field(prefix + "CoverUrl")
                thumbnail_url = if (coverRule.contains("@js:", ignoreCase = true)) {
                    null // JS transforms are not executed; preserve search results without a cover.
                } else {
                    JsonPathSubset.text(item, coverRule).takeIf { it.isNotBlank() }
                        ?.let { resolveUrl(it, sourceUrl) }
                }
            }
        }
    }

    private fun flattenJson(values: List<Any>): List<Any> =
        values.flatMap { node ->
            if (node is JSONArray) {
                (0 until node.length()).mapNotNull { node.opt(it) }
            } else {
                listOf(node)
            }
        }

    private fun isJsonRule(expression: String): Boolean =
        expression.trim().startsWith("$") || expression.trim().startsWith("-$")

    private suspend fun cataloguePage(address: String, prefix: String): MangasPage {
        val listRule = field(prefix + "List").trim()
        val jsonMode = isJsonRule(listRule)
        val items = if (jsonMode) {
            val (body, finalUrl) = fetch(address)
            jsonList(body, finalUrl, prefix)
        } else {
            list(document(address), prefix)
        }
        return MangasPage(items, false)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val template = field("ruleFindUrl")
        if (template.isBlank()) return MangasPage(emptyList(), false)
        return cataloguePage(prepareUrl(template, "", page), "ruleFind")
    }

    private fun prepareUrl(template: String, query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val first = template.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        require(!first.contains("@js:", ignoreCase = true)) { "Script-generated URLs are unsupported" }
        return resolveUrl(
            first.substringAfter("::", first)
                .replace("{{key}}", encoded)
                .replace("{{searchKey}}", encoded)
                .replace("{{keyword}}", encoded)
                .replace("{{page-1}}", (page - 1).toString())
                .replace("{{page+1}}", (page + 1).toString())
                .replace("{{page}}", page.toString())
                .replace("{page}", page.toString()),
        )
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val template = field("ruleSearchUrl")
        require(template.isNotBlank()) { "Source has no ruleSearchUrl" }
        return cataloguePage(prepareUrl(template, query, page), "ruleSearch")
    }

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val chapterListRule = field("ruleChapterList")
        val jsonMode = isJsonRule(chapterListRule) || isJsonRule(field("ruleBookName"))
        if (jsonMode) {
            val (body, finalUrl) = fetch(manga.url)
            val root = JsonPathSubset.decode(body)
            val updated = manga.copy()
            if (fetchDetails) {
                field("ruleBookName").takeIf { it.isNotBlank() }?.let {
                    updated.title = JsonPathSubset.text(root, it).ifBlank { manga.title }
                }
                field("ruleBookIntro").takeIf { it.isNotBlank() }?.let { updated.description = JsonPathSubset.text(root, it) }
                field("ruleBookAuthor").takeIf { it.isNotBlank() }?.let { updated.author = JsonPathSubset.text(root, it) }
                field("ruleBookCoverUrl").takeIf { it.isNotBlank() }?.let {
                    updated.thumbnail_url = JsonPathSubset.text(root, it).takeIf { cover -> cover.isNotBlank() }
                        ?.let { cover -> resolveUrl(cover, finalUrl) }
                }
                updated.initialized = true
            }
            val parsed = if (fetchChapters && chapterListRule.isNotBlank()) {
                val urlRule = field("ruleChapterUrl").ifBlank { field("ruleContentUrl") }
                require(urlRule.isNotBlank()) { "JSON source has no chapter URL template" }
                flattenJson(JsonPathSubset.select(root, chapterListRule)).mapNotNull { item ->
                    val chapterName = JsonPathSubset.text(item, field("ruleChapterName"))
                    val address = JsonPathSubset.text(item, urlRule)
                    if (chapterName.isBlank() || address.isBlank()) {
                        null
                    } else {
                        SChapter.create().apply {
                            name = chapterName
                            url = resolveUrl(address, finalUrl)
                        }
                    }
                }
            } else {
                chapters
            }
            return SMangaUpdate(updated, parsed)
        }
        val doc = document(manga.url)
        val updated = manga.copy()
        if (fetchDetails) {
            field("ruleBookName").takeIf { it.isNotBlank() }?.let {
                updated.title = value(doc, it).ifBlank { manga.title }
            }
            field("ruleBookIntro").takeIf { it.isNotBlank() }?.let { updated.description = value(doc, it) }
            field("ruleBookAuthor").takeIf { it.isNotBlank() }?.let { updated.author = value(doc, it) }
            field("ruleBookCoverUrl").takeIf { it.isNotBlank() }?.let {
                updated.thumbnail_url = value(doc, it).takeIf(String::isNotBlank)?.let { cover -> resolveUrl(cover, doc.location()) }
            }
            updated.initialized = true
        }
        val parsedChapters = if (!fetchChapters) {
            chapters
        } else {
            val listRule = field("ruleChapterList")
            if (listRule.isBlank()) {
                emptyList()
            } else {
                select(doc, listRule).mapNotNull { node ->
                    val title = value(node, field("ruleChapterName").ifBlank { "a@text" })
                    val address = value(node, field("ruleChapterUrl").ifBlank { "a@href" })
                    if (title.isBlank() || address.isBlank()) {
                        null
                    } else {
                        SChapter.create().apply {
                            name = title
                            url = resolveUrl(address, doc.location())
                        }
                    }
                }
            }
        }
        return SMangaUpdate(updated, parsedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rule = field("ruleBookContent").ifBlank { field("ruleContent") }
        require(rule.isNotBlank()) { "Source has no image extraction rule" }
        if (isJsonRule(rule)) {
            val (body, finalUrl) = fetch(chapter.url)
            val records = flattenJson(JsonPathSubset.select(JsonPathSubset.decode(body), rule))
            return records.mapNotNull { node ->
                val address = if (node is JSONObject) node.optString("url") else node.toString()
                address.takeIf { it.isNotBlank() && it != "null" }?.let { resolveUrl(it, finalUrl) }
            }.distinct().mapIndexed { index, address -> Page(index, address, address) }
        }
        val doc = document(chapter.url)
        val attr = rule.substringAfter('@', "src").trim()
        return select(doc, rule).mapNotNull { node ->
            photoUrl(node, attr).takeIf { it.isNotBlank() }
        }.distinct().mapIndexed { index, address -> Page(index, address, address) }
    }
}
