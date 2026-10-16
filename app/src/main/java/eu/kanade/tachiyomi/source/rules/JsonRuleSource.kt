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
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Conservative subset of PipiMiao-style CSS rules.
 * Selectors: CSS, text, href, img@src, selector@attribute.
 * Unsupported @js: rules fail closed; no arbitrary imported script runs.
 */
class JsonRuleSource(private val rule: JSONObject) : HttpSource() {
    override val name: String = rule.optString("bookSourceName").ifBlank { rule.optString("name") }
    override val lang: String = rule.optString("bookSourceLanguage").ifBlank { "zh" }
    override val baseUrl: String = rule.optString("bookSourceUrl").ifBlank { rule.optString("baseUrl") }.trimEnd('/')
    override val supportsLatest: Boolean = false
    override val id: Long = run {
        val hash = MessageDigest.getInstance("SHA-256").digest(("json-rule:" + baseUrl).toByteArray())
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (hash[i].toLong() and 255L)
        value or Long.MIN_VALUE
    }

    private fun selector(key: String): String = rule.optString(key).trim()
    private fun absolute(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else
            java.net.URI(baseUrl + "/").resolve(value).toString()

    private suspend fun document(url: String): Document = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(absolute(url)).headers(headers).build()).execute()
        response.use { res ->
            check(res.isSuccessful) { "HTTP ${res.code} for ${res.request.url}" }
            val body = res.body?.string() ?: error("Empty response")
            Jsoup.parse(body, res.request.url.toString())
        }
    }

    private fun elements(root: Element, expression: String): List<Element> {
        require(!expression.startsWith("@js:")) { "Imported JavaScript rules are unsupported" }
        val css = expression.substringBefore("@").trim().takeUnless { it in setOf("text", "href", "src", "html") } ?: ""
        return if (css.isBlank()) listOf(root) else root.select(css)
    }

    private fun extract(root: Element, expression: String): String {
        require(!expression.startsWith("@js:")) { "Imported JavaScript rules are unsupported" }
        val element = elements(root, expression).firstOrNull() ?: return ""
        val attribute = expression.substringAfter("@", expression).trim()
        return when (attribute) {
            "", "text" -> element.text()
            "html" -> element.html()
            "href", "src" -> element.absUrl(attribute).ifBlank { element.attr(attribute) }
            else -> element.attr(attribute)
        }.trim()
    }

    private fun list(doc: Document, prefix: String): List<SManga> {
        val container = selector(prefix + "List")
        if (container.isBlank()) return emptyList()
        return elements(doc, container).mapNotNull { node ->
            val title = extract(node, selector(prefix + "Name"))
            val url = extract(node, selector(prefix + "NoteUrl").ifBlank { "a@href" })
            if (title.isBlank() || url.isBlank()) null else SManga.create().apply {
                this.title = title
                this.url = absolute(url)
                thumbnail_url = extract(node, selector(prefix + "CoverUrl")).takeIf { it.isNotBlank() }?.let(::absolute)
            }
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val template = selector("ruleFindUrl")
        if (template.isBlank()) return MangasPage(emptyList(), false)
        val url = template.substringAfter("::", template).replace("{{page}}", page.toString())
        return MangasPage(list(document(url), "ruleFind"), false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val template = selector("ruleSearchUrl")
        require(template.isNotBlank()) { "Source does not define ruleSearchUrl" }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = template.substringAfter("::", template)
            .replace("{{key}}", encoded).replace("{{searchKey}}", encoded)
            .replace("{{page}}", page.toString())
        return MangasPage(list(document(url), "ruleSearch"), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val doc = document(manga.url)
        val updated = manga.copy()
        if (fetchDetails) {
            selector("ruleBookName").takeIf { it.isNotBlank() }?.let { updated.title = extract(doc, it).ifBlank { manga.title } }
            selector("ruleBookIntro").takeIf { it.isNotBlank() }?.let { updated.description = extract(doc, it) }
            updated.initialized = true
        }
        val parsed = if (fetchChapters) {
            val css = selector("ruleChapterList")
            if (css.isBlank()) emptyList() else elements(doc, css).mapNotNull { element ->
                val title = extract(element, selector("ruleChapterName").ifBlank { "text" })
                val url = extract(element, selector("ruleChapterUrl").ifBlank { "a@href" })
                if (title.isBlank() || url.isBlank()) null else SChapter.create().apply {
                    name = title
                    this.url = absolute(url)
                }
            }
        } else chapters
        return SMangaUpdate(updated, parsed)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = document(chapter.url)
        val css = selector("ruleBookContent").ifBlank { selector("ruleContent") }
        require(css.isNotBlank()) { "Source does not define page extraction rules" }
        val elements = elements(doc, css)
        return elements.mapNotNull { element ->
            val candidate = extract(element, if (css.contains("@")) css.substringAfterLast(' ').let {
                if (it.contains('@')) it else "img@src"
            } else "img@src")
            candidate.takeIf { it.isNotBlank() }?.let(::absolute)
        }.mapIndexed { index, url -> Page(index, url, url) }
    }
}
