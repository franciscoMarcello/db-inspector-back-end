package com.chico.dbinspector.report

import net.sf.jasperreports.engine.DefaultJasperReportsContext
import net.sf.jasperreports.engine.JRException
import net.sf.jasperreports.engine.JRField
import net.sf.jasperreports.engine.JRParameter
import net.sf.jasperreports.engine.JRPropertiesUtil
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperExportManager
import net.sf.jasperreports.engine.JasperFillManager
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

@Service
class ReportPdfService {
    companion object {
        private const val LOGO_RESOURCE_PATH = "reports/logo.png"
        private const val JASPER_JDT_COMPILER = "net.sf.jasperreports.jdt.JRJdtCompiler"
    }

    private val log = LoggerFactory.getLogger(ReportPdfService::class.java)

    fun generatePdf(
        reportId: java.util.UUID,
        entity: ReportEntity,
        execution: ExecutionResult,
        request: ReportRunRequest
    ): ByteArray {
        val totalStart = System.nanoTime()
        val jasperTemplate = entity.jasperTemplate ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Relatorio sem template Jasper vinculado"
        )

        val sanitizedJrxml = sanitizeJrxml(jasperTemplate.jrxml)
        val templateBytes = sanitizedJrxml.toByteArray(StandardCharsets.UTF_8)

        try {
            configureJasperCompiler()
            val compileStart = System.nanoTime()
            val jasperReport = ByteArrayInputStream(templateBytes).use { input ->
                JasperCompileManager.compileReport(input)
            }
            val compileMs = (System.nanoTime() - compileStart) / 1_000_000

            val coerceRowsStart = System.nanoTime()
            val dataRows = if (request.safe) {
                coerceRowsForTemplate(execution.allRows, jasperReport.mainDataset.fields)
            } else {
                execution.allRows
            }
            val coerceRowsMs = (System.nanoTime() - coerceRowsStart) / 1_000_000

            val dataSource = JRMapCollectionDataSource(dataRows)
            val coerceParamsStart = System.nanoTime()
            val logoBytes = loadLogoBytes()
            val jasperParams = mutableMapOf<String, Any?>(
                "REPORT_NAME" to entity.name,
                "REPORT_QUERY" to execution.query
            ).apply {
                if (request.safe) {
                    putAll(coerceParamsForTemplate(request.params, jasperReport.parameters))
                } else {
                    putAll(request.params)
                }
                if (logoBytes != null) {
                    put("LOGO_STREAM", ByteArrayInputStream(logoBytes))
                    put("LOGO_URL", this@ReportPdfService::class.java.classLoader.getResource(LOGO_RESOURCE_PATH)?.toExternalForm())
                }
            }
            val coerceParamsMs = (System.nanoTime() - coerceParamsStart) / 1_000_000

            val fillStart = System.nanoTime()
            val jasperPrint = JasperFillManager.fillReport(jasperReport, jasperParams, dataSource)
            val fillMs = (System.nanoTime() - fillStart) / 1_000_000

            val exportStart = System.nanoTime()
            val pdfBytes = JasperExportManager.exportReportToPdf(jasperPrint)
            val exportMs = (System.nanoTime() - exportStart) / 1_000_000
            val totalMs = (System.nanoTime() - totalStart) / 1_000_000

            log.info(
                "PDF benchmark reportId={} templateId={} safe={} rows={} cols={} sqlMs={} compileMs={} coerceRowsMs={} coerceParamsMs={} fillMs={} exportMs={} totalMs={}",
                reportId,
                jasperTemplate.id,
                request.safe,
                execution.allRows.size,
                execution.columns.size,
                execution.elapsedMs,
                compileMs,
                coerceRowsMs,
                coerceParamsMs,
                fillMs,
                exportMs,
                totalMs
            )
            return pdfBytes
        } catch (ex: JRException) {
            log.error(
                "Falha ao gerar PDF Jasper reportId={} templateId={} templateName={} error={}",
                reportId,
                jasperTemplate.id,
                jasperTemplate.name,
                ex.message,
                ex
            )
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Falha ao gerar PDF Jasper: ${ex.message}", ex)
        } catch (ex: IllegalArgumentException) {
            log.warn(
                "Validacao falhou na geracao de PDF reportId={} templateId={} motivo={}",
                reportId,
                jasperTemplate.id,
                ex.message
            )
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "Erro de validacao", ex)
        }
    }

    private fun sanitizeJrxml(raw: String): String {
        var result = raw
        result = result.replace(
            Regex("""(<\s*jasperReport\b[^>]*?)\s+uuid\s*=\s*"[^"]*"([^>]*>)""", RegexOption.IGNORE_CASE)
        ) { match -> "${match.groupValues[1]}${match.groupValues[2]}" }
        result = result.replace(
            Regex("""<\s*queryString\b[^>]*>(.*?)<\s*/\s*queryString\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { match -> "<query language=\"sql\">${match.groupValues[1]}</query>" }
        result = result.replace(
            Regex("""<\s*query\b[^>]*language\s*=\s*"json"[^>]*>.*?<\s*/\s*query\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { "<query language=\"sql\"><![CDATA[]]></query>" }
        return result
    }

    private fun configureJasperCompiler() {
        val context = DefaultJasperReportsContext.getInstance()
        val properties = JRPropertiesUtil.getInstance(context)
        properties.setProperty("net.sf.jasperreports.compiler.class", JASPER_JDT_COMPILER)
        properties.setProperty("net.sf.jasperreports.compiler.java", JASPER_JDT_COMPILER)
        properties.setProperty("net.sf.jasperreports.default.font.name", "DejaVu Sans")
        properties.setProperty("net.sf.jasperreports.default.pdf.font.name", "DejaVu Sans")
        properties.setProperty("net.sf.jasperreports.default.pdf.encoding", "Identity-H")
        properties.setProperty("net.sf.jasperreports.default.pdf.embedded", "true")
    }

    private fun loadLogoBytes(): ByteArray? {
        val stream = this::class.java.classLoader.getResourceAsStream(LOGO_RESOURCE_PATH)
        if (stream == null) {
            log.warn("Logo fixa nao encontrada em classpath: {}", LOGO_RESOURCE_PATH)
            return null
        }
        return stream.use { it.readBytes() }
    }

    private fun coerceRowsForTemplate(rows: List<Map<String, Any?>>, fields: Array<JRField>): List<Map<String, Any?>> {
        val expectedTypesByField = fields.associate { it.name to it.valueClassName }
        return rows.map { row ->
            row.mapValues { (key, value) -> coerceForExpectedType(value, expectedTypesByField[key], "field '$key'") }
        }
    }

    private fun coerceParamsForTemplate(params: Map<String, Any?>, reportParameters: Array<JRParameter>): Map<String, Any?> {
        val expectedTypesByParam = reportParameters.filterNot { it.isSystemDefined }.associate { it.name to it.valueClassName }
        return params.mapValues { (key, value) -> coerceForExpectedType(value, expectedTypesByParam[key], "param '$key'") }
    }

    private fun coerceForExpectedType(value: Any?, expectedClassName: String?, label: String): Any? {
        if (value == null || expectedClassName.isNullOrBlank()) return value
        return runCatching {
            when (expectedClassName) {
                "java.lang.String" -> value.toString()
                "java.lang.Long", "long" -> toBigDecimal(value).toLong()
                "java.lang.Integer", "int" -> toBigDecimal(value).toInt()
                "java.lang.Short", "short" -> toBigDecimal(value).toShort()
                "java.lang.Byte", "byte" -> toBigDecimal(value).toByte()
                "java.lang.Double", "double" -> toBigDecimal(value).toDouble()
                "java.lang.Float", "float" -> toBigDecimal(value).toFloat()
                "java.math.BigDecimal" -> toBigDecimal(value)
                "java.math.BigInteger" -> toBigInteger(value)
                "java.lang.Boolean", "boolean" -> toBooleanValue(value)
                "java.util.Date" -> toDateValue(value)
                else -> value
            }
        }.getOrElse { ex ->
            log.warn(
                "Safe mode: nao foi possivel converter {} para {} (valor='{}'). Usando null. motivo={}",
                label,
                expectedClassName,
                value,
                ex.message
            )
            null
        }
    }

    private fun toBigDecimal(value: Any): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is BigInteger -> BigDecimal(value)
            is Number -> BigDecimal(value.toString())
            is String -> value.trim().let { BigDecimal(it) }
            else -> throw IllegalArgumentException("valor nao numerico")
        }

    private fun toBigInteger(value: Any): BigInteger =
        when (value) {
            is BigInteger -> value
            is BigDecimal -> value.toBigInteger()
            is Number -> BigInteger(value.toString())
            is String -> value.trim().let { BigInteger(it) }
            else -> throw IllegalArgumentException("valor nao inteiro")
        }

    private fun toBooleanValue(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "y", "sim", "s" -> true
                "false", "0", "no", "n", "nao", "não" -> false
                else -> throw IllegalArgumentException("valor boolean invalido")
            }
            else -> throw IllegalArgumentException("valor boolean invalido")
        }

    private fun toDateValue(value: Any): Date =
        when (value) {
            is Date -> value
            is LocalDate -> Date.from(value.atStartOfDay(ZoneId.systemDefault()).toInstant())
            is LocalDateTime -> Date.from(value.atZone(ZoneId.systemDefault()).toInstant())
            is OffsetDateTime -> Date.from(value.toInstant())
            is ZonedDateTime -> Date.from(value.toInstant())
            is String -> {
                val text = value.trim()
                runCatching { Date.from(OffsetDateTime.parse(text).toInstant()) }.getOrNull()
                    ?: runCatching { Date.from(ZonedDateTime.parse(text).toInstant()) }.getOrNull()
                    ?: runCatching { Date.from(LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()) }.getOrNull()
                    ?: runCatching { Date.from(LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant()) }.getOrNull()
                    ?: throw IllegalArgumentException("valor date invalido")
            }
            else -> throw IllegalArgumentException("valor date invalido")
        }
}
