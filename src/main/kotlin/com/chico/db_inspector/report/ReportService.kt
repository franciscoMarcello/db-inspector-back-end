package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class ReportService(
    private val repository: ReportRepository,
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun list(): List<ReportResponse> =
        repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .map { it.toResponse() }

    fun create(request: ReportRequest): ReportResponse {
        val entity = ReportEntity(
            name = request.name.trim(),
            templateName = request.templateName.trim(),
            sql = request.sql.trim(),
            description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        )
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
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
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        return repository.save(entity).toResponse()
    }

    fun delete(id: UUID) {
        if (!repository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        repository.deleteById(id)
    }

    fun run(id: UUID, ctx: UpstreamContext): ReportRunResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        val query = entity.sql.trim()
        require(query.isNotBlank()) { "SQL nao pode ser vazia" }

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
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }
}
