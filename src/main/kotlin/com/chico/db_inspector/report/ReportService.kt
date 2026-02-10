package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class ReportService(
    private val repository: ReportRepository,
    private val folderRepository: ReportFolderRepository,
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
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
            folder = resolveFolder(request.folderId)
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
        entity.folder = resolveFolder(request.folderId)
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
        val queryTemplate = entity.sql.trim()
        require(queryTemplate.isNotBlank()) { "SQL nao pode ser vazia" }
        val query = buildQueryWithParams(queryTemplate, entity.variables, request.params)

        val start = System.nanoTime()
        val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val tabular = EmailReportFormatter.toTabular(result)
        val columns = tabular?.columns ?: emptyList()
        val allRows = tabular?.rows?.map { row ->
            columns.zip(row).associate { (column, value) -> column to value }
        } ?: emptyList()

        val totalRows = allRows.size
        val maxRows = properties.reports.maxRows
        val truncated = totalRows > maxRows
        val rows = if (truncated) allRows.take(maxRows) else allRows
        val summaries = computeSummaries(columns, allRows)

        val now = ZonedDateTime.now(clock)
        val meta = ReportRunMeta(
            environment = properties.environment,
            generatedAt = now.format(timestampFormatter),
            lastRunAt = now.format(timestampFormatter),
            rowCount = totalRows,
            elapsedMs = elapsedMs,
            truncated = truncated
        )

        return ReportRunResponse(
            name = entity.name,
            meta = meta,
            query = query,
            columns = columns,
            rows = rows,
            summaries = summaries
        )
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
            folder = folder?.let { reportFolder ->
                ReportFolderSummaryResponse(
                    id = reportFolder.id?.toString() ?: error("id da pasta ausente"),
                    name = reportFolder.name
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
