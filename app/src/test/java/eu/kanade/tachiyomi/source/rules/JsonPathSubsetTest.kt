package eu.kanade.tachiyomi.source.rules

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonPathSubsetTest {
    private val fixture = """{
        "data": {
            "data": [
                {"comic_name":"Comic One","comic_id":1},
                {"comic_name":"Comic Two","comic_id":2}
            ]
        }
    }""".trimIndent()

    @Test
    fun `nested arrays and alternate paths`() {
        val root = JsonPathSubset.decode(fixture)
        val result = JsonPathSubset.select(root, "$.data.data||$.missing")
        assertEquals(1, result.size) // The caller expands a list-valued terminal field.
        assertEquals(2, (result.first() as org.json.JSONArray).length())
    }

    @Test
    fun `array wildcard and per-record values`() {
        val root = JsonPathSubset.decode(fixture)
        val values = JsonPathSubset.select(root, "$.data.data[*]")
        assertEquals(listOf("Comic One", "Comic Two"), values.map { JsonPathSubset.text(it, "$.comic_name") })
    }

    @Test
    fun `interpolate manga details URL`() {
        val record = JSONObject("""{"comic_id":5,"comic_name":"Sample"}""")
        assertEquals("https://example.org/comic/5", JsonPathSubset.interpolate(record, "https://example.org/comic/{{$.comic_id}}"))
        assertEquals("Sample", JsonPathSubset.text(record, "$.comic_name"))
    }

    @Test
    fun `scripts are rejected`() {
        val record = JSONObject("""{"name":"Sample"}""")
        assertThrows(IllegalArgumentException::class.java) {
            JsonPathSubset.text(record, "$.name@js:result")
        }
    }
}
