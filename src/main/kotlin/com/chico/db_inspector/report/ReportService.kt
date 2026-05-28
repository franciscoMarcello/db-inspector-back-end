package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class ReportService(
    private val repository: ReportRepository,
    private val folderRepository: ReportFolderRepository,
    private val jasperTemplateRepository: ReportJasperTemplateRepository,
    private val accessControl: ReportAccessControlService,
    private val queryService: ReportQueryService,
    private val executionService: ReportExecutionService,
    private val variableService: ReportVariableService,
    private val columnsService: ReportColumnsService,
    private val pdfService: ReportPdfService,
    private val mapperService: ReportMapperService,
    private val validationService: ReportValidationService,
    private val summaryService: ReportSummaryService,
    private val comparisonService: ReportComparisonService,
    private val sqlExecClient: SqlExecClient,
    private val hanaQueryService: HanaQueryService,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    companion object {
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 1_000
    }

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val defaultFilterOptionsLimit = 100

    fun list(): List<ReportResponse> =
        repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .filter { accessControl.canViewReport(it) }
            .map { mapperService.toResponse(it) }

    fun create(request: ReportRequest): ReportResponse {
        request.folderId?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }
        val secondSql = request.secondSql?.trim().takeUnless { it.isNullOrBlank() }
        secondSql?.let { ReadOnlySqlValidator.requireReadOnly(it) }
        val entity = ReportEntity(
            name = request.name.trim(),
            templateName = request.templateName.trim(),
            sql = request.sql.trim(),
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            archived = request.archived ?: false,
            folder = resolveFolder(request.folderId),
            jasperTemplate = resolveJasperTemplate(request.jasperTemplateId),
            secondSql = secondSql,
            comparisonKey = request.comparisonKey?.trim().takeUnless { it.isNullOrBlank() }
        )
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(entity.sql)
        entity.replaceVariables(variableService.normalizeVariables(request.variables))
        return mapperService.toResponse(repository.save(entity))
    }

    fun update(id: UUID, request: ReportRequest): ReportResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.EDIT)
        request.folderId?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }
        val secondSql = request.secondSql?.trim().takeUnless { it.isNullOrBlank() }
        secondSql?.let { ReadOnlySqlValidator.requireReadOnly(it) }
        entity.name = request.name.trim()
        entity.templateName = request.templateName.trim()
        entity.sql = request.sql.trim()
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        entity.secondSql = secondSql
        entity.comparisonKey = request.comparisonKey?.trim().takeUnless { it.isNullOrBlank() }
        request.archived?.let { entity.archived = it }
        entity.folder = resolveFolder(request.folderId)
        entity.jasperTemplate = resolveJasperTemplate(request.jasperTemplateId)
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(entity.sql)
        entity.replaceVariables(variableService.normalizeVariables(request.variables))
        return mapperService.toResponse(repository.save(entity))
    }

    fun delete(id: UUID) {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.DELETE)
        repository.deleteById(id)
    }

    fun run(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ReportRunResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val page = (request.page ?: 0).coerceAtLeast(0)
        val size = (request.size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val execution = executeReportPage(entity, ctx, request.params, page, size)
        val summaries = summaryService.compute(execution.columns, execution.rows)

        val now = ZonedDateTime.now(clock)
        val meta = ReportRunMeta(
            environment = properties.environment,
            generatedAt = now.format(timestampFormatter),
            lastRunAt = now.format(timestampFormatter),
            page = page,
            size = size,
            rowCount = execution.rowCount,
            elapsedMs = execution.elapsedMs,
            truncated = execution.rowCount > ((page * size) + execution.rows.size)
        )

        return ReportRunResponse(
            name = entity.name,
            meta = meta,
            query = execution.query,
            columns = execution.columns,
            rows = execution.rows,
            summaries = summaries
        )
    }

    fun runAll(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ReportRunResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val execution = executeReport(entity, ctx, request.params)
        val summaries = summaryService.compute(execution.columns, execution.allRows)
        val totalRows = execution.allRows.size

        val now = ZonedDateTime.now(clock)
        val meta = ReportRunMeta(
            environment = properties.environment,
            generatedAt = now.format(timestampFormatter),
            lastRunAt = now.format(timestampFormatter),
            page = 0,
            size = totalRows,
            rowCount = totalRows,
            elapsedMs = execution.elapsedMs,
            truncated = false
        )

        return ReportRunResponse(
            name = entity.name,
            meta = meta,
            query = execution.query,
            columns = execution.columns,
            rows = execution.allRows,
            summaries = summaries
        )
    }

    fun compare(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ComparisonResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val secondSql = entity.secondSql?.trim().takeIf { !it.isNullOrBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Relatorio sem SQL de comparacao configurado")

        val execution1 = executeReport(entity, ctx, request.params)
        val source1 = ComparisonSource(
            label = "Sistema",
            columns = execution1.columns,
            rows = execution1.allRows,
            rowCount = execution1.allRows.size
        )

        val interpolatedSecondSql = buildQueryWithParams(secondSql, entity.variables, request.params)
        val hanaResult = hanaQueryService.exec(interpolatedSecondSql)
        val source2 = ComparisonSource(
            label = "SAP HANA",
            columns = hanaResult.columns,
            rows = hanaResult.rows,
            rowCount = hanaResult.rows.size
        )

        val comparisonKey = entity.comparisonKey?.trim().takeIf { !it.isNullOrBlank() }
        if (comparisonKey != null) {
            if (!source1.columns.contains(comparisonKey)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo de comparacao '$comparisonKey' ausente na origem Sistema")
            }
            if (!source2.columns.contains(comparisonKey)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo de comparacao '$comparisonKey' ausente na origem SAP HANA")
            }
        }

        val commonColumns = (execution1.columns.toSet() intersect hanaResult.columns.toSet()) - setOfNotNull(comparisonKey)
        val toleranceByField = comparisonService.normalizeTolerances(request.comparisonTolerances)
        val diff = if (comparisonKey != null) {
            comparisonService.buildKeyedDiff(source1, source2, comparisonKey, commonColumns, toleranceByField)
        } else {
            comparisonService.buildContentDiff(source1, source2, commonColumns, toleranceByField)
        }

        return ComparisonResponse(
            name = entity.name,
            comparisonKey = entity.comparisonKey,
            source1 = source1,
            source2 = source2,
            diff = diff
        )
    }

    fun columns(
        id: UUID,
        ctx: UpstreamContext,
        source: String = "both",
        includeTypes: Boolean = false,
        refresh: Boolean = false
    ): ReportColumnsResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)

        return columnsService.columns(entity, id, ctx, source, includeTypes, refresh)
    }

    fun generatePdf(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ByteArray {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val execution = executeReport(entity, ctx, request.params)
        return pdfService.generatePdf(id, entity, execution, request)
    }

    fun validate(request: ReportValidationRequest, ctx: UpstreamContext): ReportValidationResponse =
        validationService.validate(request, ctx)

    fun connectionTest(request: ReportConnectionTestRequest): ReportConnectionTestResponse {
        val source = request.source.trim().lowercase()
        val query = request.sql.trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }

        return when (source) {
            "sap" -> {
                val result = hanaQueryService.exec(query)
                ReportConnectionTestResponse(
                    source = source,
                    columns = result.columns,
                    rows = result.rows
                )
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Fonte nao suportada: $source")
        }
    }

    fun listVariableOptions(
        reportId: UUID,
        variableKey: String,
        ctx: UpstreamContext,
        request: ReportVariableOptionsRequest
    ): List<ReportVariableOptionResponse> {
        val entity = repository.findById(reportId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val variable = entity.variables.firstOrNull { it.key.equals(variableKey, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Variavel '$variableKey' nao encontrada")
        return try {
            val optionsSql = variableService.normalizeOptionsSql(variable.optionsSql)
            val query = buildQueryWithParams(optionsSql, entity.variables, request.params, enforceRequired = false)
            val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
            val rows = variableService.extractOptionRows(result)

            if (rows.isEmpty()) {
                variableService.extractOptionColumnsFromDescription(result)?.let { variableService.validateOptionColumns(it) }
                return emptyList()
            }

            val columns = variableService.validateOptionColumns(rows.first().keys)
            val limit = (request.limit ?: defaultFilterOptionsLimit).coerceIn(1, properties.reports.maxRows)

            rows.take(limit).map { row ->
                ReportVariableOptionResponse(
                    valor = row[columns.valor],
                    descricao = row[columns.descricao]?.toString() ?: ""
                )
            }
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "Erro de validacao", ex)
        }
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
    ): ExecutionResult = executionService.executeReport(entity, ctx, params)

    private fun executeReportPage(
        entity: ReportEntity,
        ctx: UpstreamContext,
        params: Map<String, Any?>,
        page: Int,
        size: Int
    ): PaginatedExecutionResult = executionService.executeReportPage(entity, ctx, params, page, size)

    private fun buildQueryWithParams(
        queryTemplate: String,
        variables: List<ReportVariableEntity>,
        params: Map<String, Any?>,
        enforceRequired: Boolean = true
    ): String = queryService.buildQueryWithParams(queryTemplate, variables, params, enforceRequired)

    private fun compareFieldValue(v1: Any?, v2: Any?, tolerance: BigDecimal?): FieldComparison =
        comparisonService.compareField(v1, v2, tolerance)

}
