package com.chico.dbinspector.email

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.charset.StandardCharsets

data class TabularResult(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

object EmailReportFormatter {

    data class ComparisonSummary(
        val hasDifference: Boolean,
        val onlyInSource1: Int,
        val onlyInSource2: Int,
        val differentRows: Int,
        val matchedRows: Int
    )

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
        val baseStyle = "font-family: Arial, sans-serif; font-size:12px;"
        if (tabular == null || tabular.columns.isEmpty()) {
            return """
                <div style="$baseStyle">
                  <p>Pré-visualização JSON:</p>
                  <pre style="font-size:12px;">${escapeHtml(fallbackJson)}</pre>
                </div>
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
            <div style="$baseStyle">
              <p>Pré-visualização (${limitedRows.size} linha(s))</p>
              <table border="1" cellpadding="4" cellspacing="0" style="font-size:12px;">
                <thead><tr>$header</tr></thead>
                <tbody>$body</tbody>
              </table>
              $moreNote
            </div>
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

    fun buildXlsx(
        tabular: TabularResult,
        maxRows: Int
    ): ByteArray {
        val workbook = XSSFWorkbook()
        return workbook.use { wb ->
            val sheet = wb.createSheet("report")
            val header = sheet.createRow(0)
            tabular.columns.forEachIndexed { index, column ->
                header.createCell(index).setCellValue(column)
            }

            tabular.rows.take(maxRows).forEachIndexed { rowIndex, rowValues ->
                val row = sheet.createRow(rowIndex + 1)
                rowValues.forEachIndexed { colIndex, value ->
                    row.createCell(colIndex).setCellValue(value?.toString() ?: "")
                }
            }

            tabular.columns.indices.forEach { col ->
                sheet.autoSizeColumn(col)
            }

            java.io.ByteArrayOutputStream().use { output ->
                wb.write(output)
                output.toByteArray()
            }
        }
    }

    fun buildComparisonWorkbook(
        source1Label: String,
        source1Rows: List<Map<String, Any?>>,
        source2Label: String,
        source2Rows: List<Map<String, Any?>>,
        maxRowsPerSheet: Int
    ): ByteArray {
        val workbook = XSSFWorkbook()
        return workbook.use { wb ->
            addMapRowsSheet(wb, "agromobi", source1Label, source1Rows, maxRowsPerSheet)
            addMapRowsSheet(wb, "sap", source2Label, source2Rows, maxRowsPerSheet)
            java.io.ByteArrayOutputStream().use { output ->
                wb.write(output)
                output.toByteArray()
            }
        }
    }

    fun buildExecutiveComparisonHtml(
        summary: ComparisonEmailSummary,
        title: String?,
        note: String?
    ): String {
        val isDivergence = summary.status.equals("DIVERGENCIA", ignoreCase = true)
        val badgeBg = if (isDivergence) "#fee2e2" else "#dcfce7"
        val badgeColor = if (isDivergence) "#991b1b" else "#166534"
        val totalDif = summary.apenasAgromobi + summary.apenasSap + summary.linhasDiferentes
        val totalBase = totalDif + summary.linhasCorrespondentes
        val percentual = if (totalBase == 0) 0.0 else (totalDif * 100.0 / totalBase)
        val resolvedTitle = title?.trim().takeUnless { it.isNullOrBlank() } ?: "Comparacao Agromobi x SAP"
        val resolvedNote = note?.trim().takeUnless { it.isNullOrBlank() } ?: "Consulte o anexo para detalhes por origem."
        return """
            <div style="font-family: Arial, sans-serif; color:#111827; font-size:13px; line-height:1.45;">
              <h2 style="margin:0 0 8px 0; font-size:18px;">${escapeHtml(resolvedTitle)}</h2>
              <div style="display:inline-block; padding:6px 10px; border-radius:999px; background:$badgeBg; color:$badgeColor; font-weight:700; margin-bottom:12px;">
                ${if (isDivergence) "Divergencia detectada" else "Sem divergencia"}
              </div>
              <table cellspacing="0" cellpadding="0" style="border-collapse:separate; border-spacing:10px 10px; width:100%; max-width:740px;">
                <tr>
                  ${metricCard("Apenas no Agromobi", summary.apenasAgromobi.toString())}
                  ${metricCard("Apenas no SAP", summary.apenasSap.toString())}
                </tr>
                <tr>
                  ${metricCard("Linhas divergentes", summary.linhasDiferentes.toString())}
                  ${metricCard("Linhas correspondentes", summary.linhasCorrespondentes.toString())}
                </tr>
              </table>
              <p style="margin:10px 0 0 0;"><strong>Percentual de divergencia:</strong> ${"%.2f".format(percentual)}%</p>
              <p style="margin:6px 0 0 0; color:#4b5563;">${escapeHtml(resolvedNote)}</p>
            </div>
        """.trimIndent()
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

    private fun addMapRowsSheet(
        workbook: XSSFWorkbook,
        fallbackName: String,
        label: String,
        rows: List<Map<String, Any?>>,
        maxRows: Int
    ) {
        val name = sanitizeSheetName(label.ifBlank { fallbackName })
        val sheet = workbook.createSheet(name)
        val firstRow = rows.firstOrNull()
        val columns = firstRow?.keys?.toList() ?: emptyList()
        val header = sheet.createRow(0)
        columns.forEachIndexed { index, column ->
            header.createCell(index).setCellValue(column)
        }

        rows.take(maxRows).forEachIndexed { idx, rowValues ->
            val row = sheet.createRow(idx + 1)
            columns.forEachIndexed { colIndex, key ->
                row.createCell(colIndex).setCellValue(rowValues[key]?.toString() ?: "")
            }
        }

        columns.indices.forEach { col -> sheet.autoSizeColumn(col) }
    }

    private fun sanitizeSheetName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/*?:\\[\\]]"), "_").trim()
        if (cleaned.isBlank()) return "sheet"
        return cleaned.take(31)
    }

    private fun metricCard(label: String, value: String): String = """
        <td style="vertical-align:top; width:50%;">
          <div style="border:1px solid #e5e7eb; border-radius:10px; padding:10px 12px; background:#f9fafb;">
            <div style="font-size:12px; color:#6b7280;">$label</div>
            <div style="font-size:24px; font-weight:700; color:#111827; margin-top:2px;">$value</div>
          </div>
        </td>
    """.trimIndent()
}
