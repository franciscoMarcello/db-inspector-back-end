package com.chico.dbinspector.email

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal

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

        // Count only rendered data rows in tbody; thead has its own <tr>
        val tbody = "<tbody>(.*?)</tbody>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.get(1)
        assertTrue(tbody != null)
        val rowCount = "<tr>".toRegex().findAll(tbody!!).count()
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
    fun `comparison workbook should include diferencas sheet`() {
        val differences = listOf(
            EmailComparisonDifference(
                type = ComparisonDifferenceType.ONLY_IN_SOURCE1,
                key = "K2",
                row = mapOf("Chave" to "K2", "Custo Total" to "710.23")
            ),
            EmailComparisonDifference(
                type = ComparisonDifferenceType.DIVERGENT_VALUE,
                key = "K1",
                column = "Custo Total",
                value1 = "100.50",
                value2 = "90.50",
                delta = BigDecimal("10.00"),
                row = mapOf("Chave" to "K1", "Custo Total" to "100.50")
            )
        )

        val bytes = EmailReportFormatter.buildComparisonWorkbook(
            source1Label = "agromobi",
            source1Rows = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.50")),
            source2Label = "sap",
            source2Rows = listOf(mapOf("Chave" to "K1", "Custo Total" to "90.50")),
            differences = differences,
            groups = emptyList(),
            maxRowsPerSheet = 100
        )

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            assertEquals(
                listOf("resumo", "diferencas", "agromobi", "sap"),
                (0 until workbook.numberOfSheets).map { workbook.getSheetName(it) }
            )
            val sheet = workbook.getSheet("diferencas")
            assertEquals("Valor agromobi", sheet.getRow(0).getCell(3).stringCellValue)
            assertEquals(2, sheet.lastRowNum)

            val missingRow = sheet.getRow(1)
            assertEquals("Apenas no agromobi", missingRow.getCell(0).stringCellValue)
            assertEquals("K2", missingRow.getCell(1).stringCellValue)
            assertEquals("(linha completa)", missingRow.getCell(2).stringCellValue)
            assertTrue(missingRow.getCell(3).stringCellValue.contains("Custo Total=710.23"))
            assertEquals("", missingRow.getCell(4).stringCellValue)

            val divergentRow = sheet.getRow(2)
            assertEquals("Valor divergente", divergentRow.getCell(0).stringCellValue)
            assertEquals("Custo Total", divergentRow.getCell(2).stringCellValue)
            assertEquals("100.50", divergentRow.getCell(3).stringCellValue)
            assertEquals("90.50", divergentRow.getCell(4).stringCellValue)
            assertEquals(10.0, divergentRow.getCell(5).numericCellValue)
        }
    }

    @Test
    fun `comparison workbook should keep diferencas sheet when there is no difference`() {
        val bytes = EmailReportFormatter.buildComparisonWorkbook(
            source1Label = "agromobi",
            source1Rows = listOf(mapOf("Chave" to "K1")),
            source2Label = "sap",
            source2Rows = listOf(mapOf("Chave" to "K1")),
            differences = emptyList(),
            groups = emptyList(),
            maxRowsPerSheet = 100
        )

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheet("diferencas")
            assertEquals("Nenhuma diferenca encontrada", sheet.getRow(1).getCell(0).stringCellValue)
        }
    }

    @Test
    fun `comparison workbook should include resumo sheet with numeric totals`() {
        val groups = listOf(
            ComparisonGroupSummary(
                group = "agricola.operacao",
                onlyInSource1 = 2,
                onlyInSource2 = 0,
                divergentValues = 1,
                deltaSum = BigDecimal("93.63"),
                valueOnlyInSource1 = BigDecimal("5120.39"),
                valueOnlyInSource2 = BigDecimal.ZERO
            )
        )

        val bytes = EmailReportFormatter.buildComparisonWorkbook(
            source1Label = "agromobi",
            source1Rows = listOf(mapOf("Chave" to "K1")),
            source2Label = "sap",
            source2Rows = listOf(mapOf("Chave" to "K1")),
            differences = emptyList(),
            groups = groups,
            maxRowsPerSheet = 100
        )

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheet("resumo")
            assertEquals("Apenas no agromobi", sheet.getRow(0).getCell(1).stringCellValue)
            val row = sheet.getRow(1)
            assertEquals("agricola.operacao", row.getCell(0).stringCellValue)
            assertEquals(2.0, row.getCell(1).numericCellValue)
            assertEquals(1.0, row.getCell(3).numericCellValue)
            assertEquals(93.63, row.getCell(4).numericCellValue)
            assertEquals(5120.39, row.getCell(5).numericCellValue)
        }
    }

    @Test
    fun `executive html should render groups and top differences`() {
        val detail = ComparisonEmailDetail(
            source1Label = "agromobi",
            source2Label = "sap",
            valueColumn = "Custo Total",
            topDifferences = listOf(
                EmailComparisonDifference(
                    type = ComparisonDifferenceType.DIVERGENT_VALUE,
                    key = "K1",
                    column = "Custo Total",
                    value1 = "100.50",
                    value2 = "90.50",
                    delta = BigDecimal("10.00")
                ),
                EmailComparisonDifference(
                    type = ComparisonDifferenceType.ONLY_IN_SOURCE1,
                    key = "K2",
                    row = mapOf("Chave" to "K2", "Custo Total" to "5120.39")
                )
            ),
            groups = listOf(
                ComparisonGroupSummary(
                    group = "agricola.operacao",
                    onlyInSource1 = 1,
                    onlyInSource2 = 0,
                    divergentValues = 1,
                    deltaSum = BigDecimal("10.00"),
                    valueOnlyInSource1 = BigDecimal("5120.39"),
                    valueOnlyInSource2 = BigDecimal.ZERO
                )
            )
        )

        val html = EmailReportFormatter.buildExecutiveComparisonHtml(
            summary = ComparisonEmailSummary("DIVERGENCIA", 1, 0, 1, 10),
            title = "Custo de insumo",
            note = null,
            detail = detail
        )

        assertTrue(html.contains("Resumo por grupo"))
        assertTrue(html.contains("agricola.operacao"))
        assertTrue(html.contains("Maiores divergencias (2)"))
        assertTrue(html.contains("Valor divergente"))
        assertTrue(html.contains("Apenas no agromobi"))
        // com coluna de valor definida, a linha exclusiva mostra so o valor, nao a linha inteira
        assertTrue(html.contains(">5120.39<"))
        assertTrue(!html.contains("Custo Total=5120.39"))
    }

    @Test
    fun `executive html should fall back to the whole row when there is no value column`() {
        val detail = ComparisonEmailDetail(
            source1Label = "agromobi",
            source2Label = "sap",
            valueColumn = null,
            topDifferences = listOf(
                EmailComparisonDifference(
                    type = ComparisonDifferenceType.ONLY_IN_SOURCE1,
                    key = "K2",
                    row = mapOf("Chave" to "K2", "Custo Total" to "5120.39")
                )
            ),
            groups = emptyList()
        )

        val html = EmailReportFormatter.buildExecutiveComparisonHtml(
            summary = ComparisonEmailSummary("DIVERGENCIA", 1, 0, 0, 10),
            title = null,
            note = null,
            detail = detail
        )

        assertTrue(html.contains("Custo Total=5120.39"))
        assertTrue(html.contains("(linha completa)"))
    }

    @Test
    fun `executive html without detail should keep only the metric cards`() {
        val html = EmailReportFormatter.buildExecutiveComparisonHtml(
            summary = ComparisonEmailSummary("DIVERGENCIA", 46, 0, 0, 812),
            title = null,
            note = null
        )

        assertTrue(html.contains("Apenas no Agromobi"))
        assertTrue(!html.contains("Resumo por grupo"))
        assertTrue(!html.contains("Maiores divergencias"))
    }

    @Test
    fun `pretty json should indent`() {
        val json = EmailReportFormatter.prettyJson(ObjectMapper(), mapOf("a" to 1))
        assert(json.contains("\n"))
    }
}
