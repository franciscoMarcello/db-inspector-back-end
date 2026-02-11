package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import net.sf.jasperreports.engine.JRField
import net.sf.jasperreports.engine.JRException
import net.sf.jasperreports.engine.JRParameter
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperExportManager
import net.sf.jasperreports.engine.JasperFillManager
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.UUID

@Service
class ReportService(
    private val repository: ReportRepository,
    private val folderRepository: ReportFolderRepository,
    private val jasperTemplateRepository: ReportJasperTemplateRepository,
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val log = LoggerFactory.getLogger(ReportService::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateTimeSqlFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val allowedVariableTypes = setOf("string", "number", "date", "datetime", "boolean")

    fun list(): List<ReportResponse> =
        repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .map { it.toResponse() }

    fun create(request: ReportRequest): ReportResponse {
        val entity = ReportEntity(
            name = request.name.trim(),
            templateName = request.templateName.trim(),
            sql = request.sql.trim(),
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            archived = request.archived ?: false,
            folder = resolveFolder(request.folderId),
            jasperTemplate = resolveJasperTemplate(request.jasperTemplateId)
        )
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        entity.replaceVariables(normalizeVariables(request.variables))
        return repository.save(entity).toResponse()
    }

    fun update(id: UUID, request: ReportRequest): ReportResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        entity.name = request.name.trim()
        entity.templateName = request.templateName.trim()
        entity.sql = request.sql.trim()
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        request.archived?.let { entity.archived = it }
        entity.folder = resolveFolder(request.folderId)
        entity.jasperTemplate = resolveJasperTemplate(request.jasperTemplateId)
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        entity.replaceVariables(normalizeVariables(request.variables))
        return repository.save(entity).toResponse()
    }

    fun delete(id: UUID) {
        if (!repository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        repository.deleteById(id)
    }

    fun run(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ReportRunResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        val execution = executeReport(entity, ctx, request.params)

        val totalRows = execution.allRows.size
        val maxRows = properties.reports.maxRows
        val truncated = totalRows > maxRows
        val rows = if (truncated) execution.allRows.take(maxRows) else execution.allRows
        val summaries = computeSummaries(execution.columns, execution.allRows)

        val now = ZonedDateTime.now(clock)
        val meta = ReportRunMeta(
            environment = properties.environment,
            generatedAt = now.format(timestampFormatter),
            lastRunAt = now.format(timestampFormatter),
            rowCount = totalRows,
            elapsedMs = execution.elapsedMs,
            truncated = truncated
        )

        return ReportRunResponse(
            name = entity.name,
            meta = meta,
            query = execution.query,
            columns = execution.columns,
            rows = rows,
            summaries = summaries
        )
    }

    fun generatePdf(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ByteArray {
        val totalStart = System.nanoTime()
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        val jasperTemplate = entity.jasperTemplate ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Relatorio sem template Jasper vinculado"
        )

        val execution = executeReport(entity, ctx, request.params)
        val sanitizedJrxml = sanitizeJrxml(jasperTemplate.jrxml)
        val templateBytes = sanitizedJrxml.toByteArray(StandardCharsets.UTF_8)

        try {
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
            val jasperParams = mutableMapOf<String, Any?>(
                "REPORT_NAME" to entity.name,
                "REPORT_QUERY" to execution.query
            ).apply {
                if (request.safe) {
                    putAll(coerceParamsForTemplate(request.params, jasperReport.parameters))
                } else {
                    putAll(request.params)
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
                id,
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
                id,
                jasperTemplate.id,
                jasperTemplate.name,
                ex.message,
                ex
            )
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Falha ao gerar PDF Jasper: ${ex.message}",
                ex
            )
        } catch (ex: IllegalArgumentException) {
            log.warn(
                "Validacao falhou na geracao de PDF reportId={} templateId={} motivo={}",
                id,
                jasperTemplate.id,
                ex.message
            )
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "Erro de validacao", ex)
        }
    }

    private fun computeSummaries(columns: List<String>, rows: List<Map<String, Any?>>): List<ReportSummary> =
        columns.mapNotNull { column ->
            var sum = 0.0
            var hasValue = false
            var valid = true
            for (row in rows) {
                val value = row[column] ?: continue
                when (value) {
                    is Number -> {
                        sum += value.toDouble()
                        hasValue = true
                    }
                    else -> {
                        valid = false
                        break
                    }
                }
            }
            if (valid && hasValue) ReportSummary(column = column, sum = sum) else null
        }

    private fun ReportEntity.toResponse(): ReportResponse {
        val created = createdAt ?: error("createdAt ausente")
        val updated = updatedAt ?: error("updatedAt ausente")
        return ReportResponse(
            id = id?.toString() ?: error("id ausente"),
            name = name,
            templateName = templateName,
            sql = sql,
            description = description,
            archived = archived,
            folder = folder?.let { reportFolder ->
                ReportFolderSummaryResponse(
                    id = reportFolder.id?.toString() ?: error("id da pasta ausente"),
                    name = reportFolder.name,
                    archived = reportFolder.archived
                )
            },
            jasperTemplate = jasperTemplate?.let { template ->
                JasperTemplateSummaryResponse(
                    id = template.id?.toString() ?: error("id do template Jasper ausente"),
                    name = template.name,
                    archived = template.archived
                )
            },
            variables = variables
                .sortedBy { it.orderIndex }
                .map { variable ->
                    ReportVariableResponse(
                        id = variable.id?.toString() ?: error("id da variavel ausente"),
                        key = variable.key,
                        label = variable.label,
                        type = variable.type,
                        required = variable.required,
                        defaultValue = variable.defaultValue,
                        orderIndex = variable.orderIndex
                    )
                },
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }

    private fun resolveFolder(folderId: UUID?): ReportFolderEntity? {
        if (folderId == null) return null
        return folderRepository.findById(folderId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
        }
    }

    private fun resolveJasperTemplate(templateId: UUID?): ReportJasperTemplateEntity? {
        if (templateId == null) return null
        return jasperTemplateRepository.findById(templateId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
        }
    }

    private fun executeReport(
        entity: ReportEntity,
        ctx: UpstreamContext,
        params: Map<String, Any?>
    ): ExecutionResult {
        val queryTemplate = entity.sql.trim()
        require(queryTemplate.isNotBlank()) { "SQL nao pode ser vazia" }
        val query = buildQueryWithParams(queryTemplate, entity.variables, params)

        val start = System.nanoTime()
        val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val tabular = EmailReportFormatter.toTabular(result)
        val columns = tabular?.columns ?: emptyList()
        val allRows = tabular?.rows?.map { row ->
            columns.zip(row).associate { (column, value) -> column to value }
        } ?: emptyList()

        return ExecutionResult(
            query = query,
            columns = columns,
            allRows = allRows,
            elapsedMs = elapsedMs
        )
    }

    private fun buildQueryWithParams(
        queryTemplate: String,
        variables: List<ReportVariableEntity>,
        params: Map<String, Any?>
    ): String {
        if (variables.isEmpty()) return queryTemplate

        val knownKeys = variables.map { it.key }.toSet()
        val unknown = params.keys.filterNot { knownKeys.contains(it) }
        require(unknown.isEmpty()) { "Parametros desconhecidos: ${unknown.joinToString(", ")}" }

        var rendered = queryTemplate
        variables.forEach { variable ->
            val rawValue = resolveVariableValue(variable, params)
            val sqlLiteral = toSqlLiteral(variable, rawValue)
            val placeholder = Regex("(?<!:):${Regex.escape(variable.key)}\\b")
            rendered = rendered.replace(placeholder, sqlLiteral)
        }
        return rendered
    }

    private fun resolveVariableValue(variable: ReportVariableEntity, params: Map<String, Any?>): Any? {
        if (params.containsKey(variable.key)) {
            return params[variable.key]
        }
        if (!variable.defaultValue.isNullOrBlank()) {
            return variable.defaultValue
        }
        if (variable.required) {
            throw IllegalArgumentException("Parametro obrigatorio ausente: '${variable.key}'")
        }
        return null
    }

    private fun toSqlLiteral(variable: ReportVariableEntity, rawValue: Any?): String {
        if (rawValue == null) return "NULL"

        return when (variable.type) {
            "string" -> "'${escapeSqlString(rawValue.toString())}'"
            "number" -> parseNumber(variable.key, rawValue).toPlainString()
            "date" -> "'${parseDate(variable.key, rawValue)}'"
            "datetime" -> "'${parseDateTime(variable.key, rawValue)}'"
            "boolean" -> parseBoolean(variable.key, rawValue).toString()
            else -> throw IllegalArgumentException("Tipo de variavel invalido: '${variable.type}'")
        }
    }

    private fun parseNumber(key: String, rawValue: Any): BigDecimal =
        when (rawValue) {
            is Number -> BigDecimal(rawValue.toString())
            is String -> rawValue.trim().let {
                runCatching { BigDecimal(it) }.getOrElse {
                    throw IllegalArgumentException("Parametro '$key' deve ser number")
                }
            }
            else -> throw IllegalArgumentException("Parametro '$key' deve ser number")
        }

    private fun parseDate(key: String, rawValue: Any): String {
        val text = rawValue.toString().trim()
        return try {
            LocalDate.parse(text).toString()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Parametro '$key' deve ser date (yyyy-MM-dd)")
        }
    }

    private fun parseDateTime(key: String, rawValue: Any): String {
        val text = rawValue.toString().trim()
        val value = runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: throw IllegalArgumentException("Parametro '$key' deve ser datetime (ISO-8601)")

        return value.format(dateTimeSqlFormatter)
    }

    private fun parseBoolean(key: String, rawValue: Any): Boolean =
        when (rawValue) {
            is Boolean -> rawValue
            is String -> when (rawValue.trim().lowercase()) {
                "true", "1", "yes", "y", "sim", "s" -> true
                "false", "0", "no", "n", "nao", "não" -> false
                else -> throw IllegalArgumentException("Parametro '$key' deve ser boolean")
            }
            else -> throw IllegalArgumentException("Parametro '$key' deve ser boolean")
        }

    private fun escapeSqlString(value: String): String = value.replace("'", "''")

    private fun sanitizeJrxml(raw: String): String {
        var result = raw

        // Remove uuid only from jasperReport root tag, where some generated variants are incompatible.
        result = result.replace(
            Regex("""(<\s*jasperReport\b[^>]*?)\s+uuid\s*=\s*"[^"]*"([^>]*>)""", RegexOption.IGNORE_CASE)
        ) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}"
        }

        // Jasper 7 expects <query>, so normalize any legacy <queryString> to <query language="sql">.
        result = result.replace(
            Regex("""<\s*queryString\b[^>]*>(.*?)<\s*/\s*queryString\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { match ->
            "<query language=\"sql\">${match.groupValues[1]}</query>"
        }

        // Reports are filled from JRMapCollectionDataSource; disable json query executer usage.
        result = result.replace(
            Regex("""<\s*query\b[^>]*language\s*=\s*"json"[^>]*>.*?<\s*/\s*query\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) {
            "<query language=\"sql\"><![CDATA[]]></query>"
        }

        return result
    }

    private fun coerceRowsForTemplate(
        rows: List<Map<String, Any?>>,
        fields: Array<JRField>
    ): List<Map<String, Any?>> {
        val expectedTypesByField = fields.associate { it.name to it.valueClassName }
        return rows.map { row ->
            row.mapValues { (key, value) ->
                coerceForExpectedType(value, expectedTypesByField[key], "field '$key'")
            }
        }
    }

    private fun coerceParamsForTemplate(
        params: Map<String, Any?>,
        reportParameters: Array<JRParameter>
    ): Map<String, Any?> {
        val expectedTypesByParam = reportParameters
            .filterNot { it.isSystemDefined }
            .associate { it.name to it.valueClassName }
        return params.mapValues { (key, value) ->
            coerceForExpectedType(value, expectedTypesByParam[key], "param '$key'")
        }
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
                    ?: runCatching {
                        Date.from(LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant())
                    }.getOrNull()
                    ?: runCatching {
                        Date.from(LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant())
                    }.getOrNull()
                    ?: throw IllegalArgumentException("valor date invalido")
            }
            else -> throw IllegalArgumentException("valor date invalido")
        }

    private data class ExecutionResult(
        val query: String,
        val columns: List<String>,
        val allRows: List<Map<String, Any?>>,
        val elapsedMs: Long
    )

    private fun normalizeVariables(input: List<ReportVariableRequest>): List<ReportVariableEntity> {
        val seenKeys = mutableSetOf<String>()
        return input.mapIndexed { index, variable ->
            val key = variable.key.trim()
            val label = variable.label.trim()
            val type = variable.type.trim().lowercase()
            require(type in allowedVariableTypes) {
                "Tipo de variavel invalido: '$type'. Use string, number, date, datetime ou boolean"
            }
            require(seenKeys.add(key.lowercase())) { "Variavel duplicada: '$key'" }

            ReportVariableEntity(
                key = key,
                label = label,
                type = type,
                required = variable.required,
                defaultValue = variable.defaultValue?.trim().takeUnless { it.isNullOrBlank() },
                orderIndex = variable.orderIndex ?: index
            )
        }
    }
}
