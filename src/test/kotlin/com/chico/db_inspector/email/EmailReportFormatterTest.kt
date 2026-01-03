package com.chico.dbinspector.email

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EmailReportFormatterTest {

    @Test
    fun `should convert data list of maps to tabular`() {
        val result = mapOf(
            "data" to listOf(
                mapOf("id" to 1, "name" to "foo"),
                mapOf("id" to 2, "name" to "bar")
            )
        )

        val tabular = EmailReportFormatter.toTabular(result)

        assertNotNull(tabular)
        assertEquals(listOf("id", "name"), tabular!!.columns)
        assertEquals(listOf(listOf(1, "foo"), listOf(2, "bar")), tabular.rows)
    }

    @Test
    fun `should build html preview with limited rows`() {
        val tabular = TabularResult(
            columns = listOf("id", "value"),
            rows = (1..5).map { listOf(it, "v$it") }
        )
        val html = EmailReportFormatter.buildHtmlPreview(tabular, previewLimit = 3, fallbackJson = "{}")

        // Preview should contain only first 3 rows rendered and mention more rows
        val rowCount = "<tr>".toRegex().findAll(html).count()
        assertEquals(3, rowCount)
        assert(html.contains("+2 linha(s)"))
    }

    @Test
    fun `should build csv respecting max rows`() {
        val tabular = TabularResult(
            columns = listOf("id", "value"),
            rows = (1..4).map { listOf(it, "v$it") }
        )

        val csv = EmailReportFormatter.buildCsv(tabular, maxRows = 2).toString(Charsets.UTF_8)
        val lines = csv.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size) // header + 2 rows
        assertEquals("id,value", lines.first())
    }

    @Test
    fun `pretty json should indent`() {
        val json = EmailReportFormatter.prettyJson(ObjectMapper(), mapOf("a" to 1))
        assert(json.contains("\n"))
    }
}
