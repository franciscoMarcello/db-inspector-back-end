package com.chico.dbinspector.email

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.charset.StandardCharsets

data class TabularResult(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

object EmailReportFormatter {

    fun toTabular(result: Map<String, Any?>): TabularResult? {
        val dataField = result["data"]
        if (dataField !is List<*>) return null

        val first = dataField.firstOrNull()
        val columns = when (first) {
            is Map<*, *> -> first.keys.filterIsInstance<String>()
            is List<*> -> (first.indices).map { "col_${it + 1}" }
            else -> emptyList()
        }
        if (columns.isEmpty()) return null

        val rows = dataField.mapNotNull { row ->
            when (row) {
                is Map<*, *> -> columns.map { key -> row[key] }
                is List<*> -> row.toList()
                else -> null
            }
        }
        if (rows.isEmpty()) return null

        return TabularResult(columns = columns, rows = rows)
    }

    fun buildHtmlPreview(
        tabular: TabularResult?,
        previewLimit: Int,
        fallbackJson: String
    ): String {
        if (tabular == null || tabular.columns.isEmpty()) {
            return """
                <p>Pré-visualização JSON:</p>
                <pre>${escapeHtml(fallbackJson)}</pre>
            """.trimIndent()
        }
        val limitedRows = tabular.rows.take(previewLimit)
        val more = tabular.rows.size - limitedRows.size
        val header = tabular.columns.joinToString(separator = "") { "<th>${escapeHtml(it)}</th>" }
        val body = limitedRows.joinToString(separator = "") { row ->
            val tds = row.joinToString(separator = "") { "<td>${escapeHtml(it)}</td>" }
            "<tr>$tds</tr>"
        }
        val moreNote = if (more > 0) "<p>+${more} linha(s) não exibidas no preview.</p>" else ""
        return """
            <p>Pré-visualização (${limitedRows.size} linha(s))</p>
            <table border="1" cellpadding="4" cellspacing="0">
              <thead><tr>$header</tr></thead>
              <tbody>$body</tbody>
            </table>
            $moreNote
        """.trimIndent()
    }

    fun buildCsv(
        tabular: TabularResult,
        maxRows: Int
    ): ByteArray {
        val header = tabular.columns.joinToString(",") { escapeCsv(it) }
        val rows = tabular.rows.take(maxRows).joinToString("\n") { row ->
            row.joinToString(",") { escapeCsv(it) }
        }
        val csv = if (rows.isBlank()) header else "$header\n$rows"
        return csv.toByteArray(StandardCharsets.UTF_8)
    }

    fun prettyJson(mapper: ObjectMapper, result: Map<String, Any?>): String {
        val prettyMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
        return prettyMapper.writeValueAsString(result)
    }

    private fun escapeCsv(value: Any?): String {
        val text = value?.toString() ?: ""
        val needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
        val escaped = text.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun escapeHtml(value: Any?): String {
        val text = value?.toString() ?: ""
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
