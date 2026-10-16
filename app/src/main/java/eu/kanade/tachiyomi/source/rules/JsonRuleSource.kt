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
            .digest(("tachiyomi-json-rule:" + baseUrl).toByteArray())
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

    private suspend fun document(address: String): Document = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(resolveUrl(address)).headers(headers).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}: ${response.request.url}" }
            val body = response.body?.string() ?: error("Empty document response")
            Jsoup.parse(body, response.request.url.toString())
        }
    }

    /** selector@attribute, selector@text, or bare text/href/src shorthand. */
    private fun select(root: Element, expression: String): List<Element> {
        require(!expression.contains("@js:", ignoreCase = true)) { "JavaScript rule unsupported" }
        require(!expression.startsWith("json:", ignoreCase = true)) { "JSONPath rule unsupported" }
        require(!expression.startsWith("xpath:", ignoreCase = true)) { "XPath rule unsupported" }
        val css = expression.substringBefore('@').trim()
        return if (css.isBlank() || css in setOf("text", "html", "href", "src")) {
            listOf(root)
        } else {
            root.select(css)
        }
    }

    private fun value(root: Element, expression: String): String {
        if (expression.isBlank()) return ""
        val node = select(root, expression).firstOrNull() ?: return ""
        val attr = if ('@' in expression) {
            expression.substringAfterLast('@').trim()
        } else {
            expression.takeIf { it in setOf("text", "html", "href", "src") } ?: "text"
        }
        return when (attr) {
            "", "text" -> node.text()
            "html" -> node.html()
            "href", "src" -> node.absUrl(attr).ifBlank { node.attr(attr) }
            else -> node.attr(attr)
        }.trim()
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

    override suspend fun getPopularManga(page: Int): MangasPage {
        val template = field("ruleFindUrl")
        if (template.isBlank()) return MangasPage(emptyList(), false)
        return MangasPage(list(document(prepareUrl(template, "", page)), "ruleFind"), false)
    }

    private fun prepareUrl(template: String, query: String, page: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return resolveUrl(
            template.substringAfter("::", template)
                .replace("{{key}}", encoded)
                .replace("{{searchKey}}", encoded)
                .replace("{{keyword}}", encoded)
                .replace("{{page}}", page.toString())
                .replace("{page}", page.toString()),
        )
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val template = field("ruleSearchUrl")
        require(template.isNotBlank()) { "Source has no ruleSearchUrl" }
        return MangasPage(list(document(prepareUrl(template, query, page)), "ruleSearch"), false)
    }

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
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
        val parsedChapters = if (!fetchChapters) chapters else {
            val listRule = field("ruleChapterList")
            if (listRule.isBlank()) emptyList() else select(doc, listRule).mapNotNull { node ->
                val title = value(node, field("ruleChapterName").ifBlank { "a@text" })
                val address = value(node, field("ruleChapterUrl").ifBlank { "a@href" })
                if (title.isBlank() || address.isBlank()) null else SChapter.create().apply {
                    name = title
                    url = resolveUrl(address, doc.location())
                }
            }
        }
        return SMangaUpdate(updated, parsedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = document(chapter.url)
        val rule = field("ruleBookContent").ifBlank { field("ruleContent") }
        require(rule.isNotBlank()) { "Source has no image extraction rule" }
        val attr = rule.substringAfter('@', "src").trim()
        return select(doc, rule).mapNotNull { node ->
            photoUrl(node, attr).takeIf { it.isNotBlank() }
        }.distinct().mapIndexed { index, address -> Page(index, address, address) }
    }
}
