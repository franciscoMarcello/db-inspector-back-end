package com.chico.dbinspector.dashboard

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.email.TabularResult
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class DashboardService(
    private val dashboardRepository: DashboardRepository,
    private val widgetRepository: DashboardWidgetRepository,
    private val sqlExecClient: SqlExecClient,
    private val hanaQueryService: HanaQueryService,
    private val adminAuditService: AdminAuditService,
    private val objectMapper: ObjectMapper
) {
    companion object {
        private const val DEFAULT_WIDGET_LIMIT = 500
        private const val MAX_WIDGET_LIMIT = 5_000
        private val placeholderRegex = Regex("(?<!:):([A-Za-z_][A-Za-z0-9_]*)")
        private val allowedFilterTypes = setOf("date", "number", "text")
    }

    fun list(filter: DashboardFilter): List<DashboardResponse> =
        dashboardRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .asSequence()
            .filter { filter.system == null || it.system == filter.system }
            .filter { filter.archived == null || it.archived == filter.archived }
            .map { it.toResponse() }
            .toList()

    fun get(id: UUID): DashboardResponse = findDashboard(id).toResponse()

    @Transactional
    fun create(request: DashboardRequest): DashboardResponse {
        val filters = normalizeFilters(request.filtersJson)
        val entity = DashboardEntity(
            name = request.name.trim(),
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            system = request.system,
            filtersJson = encodeFilterDefinitions(filters),
            archived = request.archived ?: false,
            createdBy = currentActor()
        )
        val saved = dashboardRepository.saveAndFlush(entity)
        return saved.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: DashboardRequest): DashboardResponse {
        val entity = findDashboard(id)
        val filters = normalizeFilters(request.filtersJson)
        entity.name = request.name.trim()
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        if (entity.system != request.system) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sistema do dashboard nao pode ser alterado")
        }
        entity.filtersJson = encodeFilterDefinitions(filters)
        request.archived?.let { entity.archived = it }
        return dashboardRepository.saveAndFlush(entity).toResponse()
    }

    @Transactional
    fun archive(id: UUID) {
        val entity = findDashboard(id)
        entity.archived = true
        dashboardRepository.save(entity)
    }

    @Transactional
    fun createWidget(dashboardId: UUID, request: DashboardWidgetRequest): DashboardWidgetResponse {
        val dashboard = findDashboard(dashboardId)
        val querySql = request.querySql.trim()
        ReadOnlySqlValidator.requireReadOnly(querySql)

        val widget = DashboardWidgetEntity(
            dashboard = dashboard,
            title = request.title.trim(),
            type = request.type,
            querySql = querySql,
            configJson = encodeJson(request.configJson),
            layoutJson = encodeJson(request.layoutJson),
            positionOrder = request.positionOrder ?: nextPosition(dashboard)
        )

        return widgetRepository.saveAndFlush(widget).toResponse()
    }

    @Transactional
    fun updateWidget(dashboardId: UUID, widgetId: UUID, request: DashboardWidgetRequest): DashboardWidgetResponse {
        val widget = findWidgetInDashboard(dashboardId, widgetId)
        val querySql = request.querySql.trim()
        ReadOnlySqlValidator.requireReadOnly(querySql)

        widget.title = request.title.trim()
        widget.type = request.type
        widget.querySql = querySql
        widget.configJson = encodeJson(request.configJson)
        widget.layoutJson = encodeJson(request.layoutJson)
        request.positionOrder?.let { widget.positionOrder = it }

        return widgetRepository.saveAndFlush(widget).toResponse()
    }

    @Transactional
    fun deleteWidget(dashboardId: UUID, widgetId: UUID) {
        val widget = findWidgetInDashboard(dashboardId, widgetId)
        widgetRepository.delete(widget)
    }

    fun previewWidget(dashboardId: UUID, widgetId: UUID, ctx: UpstreamContext, request: DashboardWidgetRunRequest): DashboardWidgetRunResponse {
        return runWidgetInternal(dashboardId, widgetId, ctx, request.limit, request.resolvedParams())
    }

    fun runWidget(dashboardId: UUID, widgetId: UUID, ctx: UpstreamContext, request: DashboardWidgetRunRequest): DashboardWidgetRunResponse {
        return runWidgetInternal(dashboardId, widgetId, ctx, request.limit, request.resolvedParams())
    }

    private fun runWidgetInternal(
        dashboardId: UUID,
        widgetId: UUID,
        ctx: UpstreamContext,
        limitOverride: Int?,
        inputParams: Map<String, Any?>
    ): DashboardWidgetRunResponse {
        val widget = findWidgetInDashboard(dashboardId, widgetId)
        val dashboard = widget.dashboard ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Widget sem dashboard")
        val limit = (limitOverride ?: DEFAULT_WIDGET_LIMIT).coerceIn(1, MAX_WIDGET_LIMIT)
        val query = toLimitedSelect(widget.querySql, limit + 1)
        val filterDefinitions = decodeFilterDefinitions(dashboard.filtersJson)
        val params = validateAndBuildParams(query, filterDefinitions, inputParams)
        val start = System.nanoTime()

        return runCatching {
            val tabular = when (dashboard.system) {
                DashboardSystem.AGROMOBI -> {
                    val raw = runCatching {
                        sqlExecClient.exec(
                            endpointUrl = ctx.endpointUrl,
                            bearer = ctx.bearer,
                            query = query,
                            params = params,
                            asDict = true,
                            withDescription = true
                        )
                    }.getOrElse {
                        val renderedQuery = renderQueryWithParams(query, params, filterDefinitions)
                        sqlExecClient.exec(
                            endpointUrl = ctx.endpointUrl,
                            bearer = ctx.bearer,
                            query = renderedQuery,
                            params = emptyMap(),
                            asDict = true,
                            withDescription = true
                        )
                    }
                    EmailReportFormatter.toTabular(raw)
                }

                DashboardSystem.SAP -> {
                    val result = hanaQueryService.exec(query, params)
                    TabularResult(
                        columns = result.columns,
                        rows = result.rows.map { row -> result.columns.map { row[it] } }
                    )
                }
            }

            val columns = tabular?.columns ?: emptyList()
            val allRows = tabular?.rows?.map { row ->
                columns.zip(row).associate { (column, value) -> column to value }
            } ?: emptyList()

            val truncated = allRows.size > limit
            val rows = if (truncated) allRows.take(limit) else allRows
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            val response = DashboardWidgetRunResponse(
                meta = WidgetRunMeta(
                    elapsedMs = elapsedMs,
                    rowCount = rows.size,
                    truncated = truncated
                ),
                columns = columns,
                rows = rows,
                chartHint = suggestChartHint(columns, rows)
            )

            auditExecution(dashboard, widget, elapsedMs, success = true, error = null)
            response
        }.getOrElse { ex ->
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            auditExecution(dashboard, widget, elapsedMs, success = false, error = ex.message)
            throw ex
        }
    }

    private fun validateAndBuildParams(
        query: String,
        filters: List<DashboardFilterDefinition>,
        incoming: Map<String, Any?>
    ): Map<String, Any?> {
        val placeholders = extractPlaceholders(query)
        if (placeholders.isEmpty()) return emptyMap()

        val filterByKey = filters.associateBy { it.key }
        val incomingNormalized = incoming.entries.associate { it.key.lowercase() to it.value }
        val filterByLower = filters.associateBy { it.key.lowercase() }
        val unknownSent = incoming.keys.filterNot { filterByLower.containsKey(it.lowercase()) }
        if (unknownSent.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametros desconhecidos: ${unknownSent.joinToString(", ")}")
        }

        val result = mutableMapOf<String, Any?>()
        placeholders.forEach { key ->
            val filter = filterByKey[key] ?: filterByLower[key.lowercase()]
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro '$key' nao cadastrado no dashboard")

            val normalizedKey = filter.key.lowercase()
            val raw = if (incomingNormalized.containsKey(normalizedKey)) incomingNormalized[normalizedKey] else filter.defaultValue
            if (raw == null || raw.toString().trim().isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro obrigatorio ausente: '$key'")
            }

            result[key] = coerceFilterValue(filter, raw)
        }

        return result
    }

    private fun coerceFilterValue(filter: DashboardFilterDefinition, raw: Any): Any {
        return when (normalizeFilterType(filter.type)) {
            "date" -> {
                val text = raw.toString().trim()
                try {
                    LocalDate.parse(text)
                    text
                } catch (_: DateTimeParseException) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro '${filter.key}' deve ser date (yyyy-MM-dd)")
                }
            }

            "number" -> {
                runCatching { BigDecimal(raw.toString().trim()) }.getOrElse {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro '${filter.key}' deve ser number")
                }
            }

            "text" -> raw.toString()
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de filtro invalido: '${filter.type}'")
        }
    }

    private fun renderQueryWithParams(
        query: String,
        params: Map<String, Any?>,
        filters: List<DashboardFilterDefinition>
    ): String {
        val filterByKey = filters.associateBy { it.key.lowercase() }
        var rendered = query
        params.forEach { (key, value) ->
            val filter = filterByKey[key.lowercase()] ?: return@forEach
            val literal = toSqlLiteral(filter, value)
            val placeholder = Regex("(?<!:):${Regex.escape(filter.key)}\b", RegexOption.IGNORE_CASE)
            rendered = rendered.replace(placeholder, literal)
        }
        return rendered
    }

    private fun toSqlLiteral(filter: DashboardFilterDefinition, value: Any?): String {
        if (value == null) return "NULL"
        return when (normalizeFilterType(filter.type)) {
            "date", "text" -> "'${value.toString().replace("'", "''")}'"
            "number" -> value.toString()
            else -> "'${value.toString().replace("'", "''")}'"
        }
    }

    private fun extractPlaceholders(query: String): Set<String> =
        placeholderRegex.findAll(query).map { it.groupValues[1] }.toSet()

    private fun normalizeFilters(filters: List<DashboardFilterDefinition>?): List<DashboardFilterDefinition> {
        if (filters.isNullOrEmpty()) return emptyList()
        val seen = mutableSetOf<String>()

        return filters.map { filter ->
            val key = filter.key.trim()
            val label = filter.label.trim()
            val type = normalizeFilterType(filter.type)
            if (key.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro com key vazio")
            if (!seen.add(key)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro duplicado: '$key'")
            if (label.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro '$key' com label vazio")
            if (!allowedFilterTypes.contains(type)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de filtro invalido: '$type'. Use date, number ou text")
            }

            DashboardFilterDefinition(
                key = key,
                label = label,
                type = type,
                defaultValue = filter.defaultValue?.trim().takeUnless { it.isNullOrBlank() }
            )
        }
    }

    private fun normalizeFilterType(raw: String): String {
        return when (raw.trim().lowercase()) {
            "date", "data" -> "date"
            "number", "numero", "número" -> "number"
            "text", "texto", "string" -> "text"
            else -> raw.trim().lowercase()
        }
    }

    private fun auditExecution(
        dashboard: DashboardEntity,
        widget: DashboardWidgetEntity,
        elapsedMs: Long,
        success: Boolean,
        error: String?
    ) {
        adminAuditService.log(
            action = "DASHBOARD_WIDGET_EXECUTE",
            targetType = "DASHBOARD_WIDGET",
            targetId = widget.id?.toString(),
            details = mapOf(
                "dashboardId" to dashboard.id?.toString(),
                "widgetId" to widget.id?.toString(),
                "system" to dashboard.system.name,
                "durationMs" to elapsedMs,
                "success" to success,
                "error" to error
            )
        )
    }

    private fun suggestChartHint(columns: List<String>, rows: List<Map<String, Any?>>): ChartHint? {
        if (columns.isEmpty() || rows.isEmpty()) return null
        val first = rows.first()

        val metric = columns.firstOrNull { col -> first[col] is Number }
        val dimension = columns.firstOrNull { col -> first[col] is String || first[col] is Boolean } ?: columns.firstOrNull { it != metric }

        val suggestedType = when {
            metric == null -> "table"
            dimension == null -> "kpi"
            else -> "bar"
        }

        return ChartHint(
            dimension = dimension,
            metric = metric,
            suggestedType = suggestedType
        )
    }

    private fun toLimitedSelect(rawSql: String, limit: Int): String {
        val sql = rawSql.trim().trimEnd(';').trim()
        require(sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(sql)
        return "SELECT * FROM ($sql) dbi_widget LIMIT $limit"
    }

    private fun findDashboard(id: UUID): DashboardEntity =
        dashboardRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard nao encontrado")
        }

    private fun findWidgetInDashboard(dashboardId: UUID, widgetId: UUID): DashboardWidgetEntity {
        val widget = widgetRepository.findById(widgetId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nao encontrado")
        }
        val currentDashboardId = widget.dashboard?.id
        if (currentDashboardId != dashboardId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Widget nao pertence ao dashboard informado")
        }
        return widget
    }

    private fun currentActor(): String {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthUserPrincipal
        return principal?.username ?: "system"
    }

    private fun nextPosition(dashboard: DashboardEntity): Int {
        val max = dashboard.widgets.maxOfOrNull { it.positionOrder } ?: -1
        return max + 1
    }

    private fun DashboardEntity.toResponse(): DashboardResponse {
        val created = createdAt ?: Instant.now()
        val updated = updatedAt ?: created
        val dashId = id ?: throw IllegalStateException("Dashboard sem id")
        return DashboardResponse(
            id = dashId.toString(),
            name = name,
            description = description,
            system = system,
            filtersJson = decodeFilterDefinitions(filtersJson),
            archived = archived,
            createdBy = createdBy,
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli(),
            widgets = widgets.sortedBy { it.positionOrder }.map { it.toResponse() }
        )
    }

    private fun DashboardWidgetEntity.toResponse(): DashboardWidgetResponse {
        val created = createdAt ?: Instant.now()
        val updated = updatedAt ?: created
        val widgetId = id ?: throw IllegalStateException("Widget sem id")
        val dashId = dashboard?.id ?: throw IllegalStateException("Widget sem dashboard")

        return DashboardWidgetResponse(
            id = widgetId.toString(),
            dashboardId = dashId.toString(),
            title = title,
            type = type,
            querySql = querySql,
            configJson = decodeJson(configJson),
            layoutJson = decodeJson(layoutJson),
            positionOrder = positionOrder,
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }

    private fun encodeJson(value: JsonNode?): String? {
        if (value == null || value.isNull) return null
        return objectMapper.writeValueAsString(value)
    }

    private fun decodeJson(value: String?): JsonNode {
        if (value.isNullOrBlank()) return emptyJsonNode()
        return objectMapper.readTree(value)
    }

    private fun encodeFilterDefinitions(filters: List<DashboardFilterDefinition>): String? {
        if (filters.isEmpty()) return null
        return objectMapper.writeValueAsString(filters)
    }

    private fun decodeFilterDefinitions(value: String?): List<DashboardFilterDefinition> {
        if (value.isNullOrBlank()) return emptyList()
        return objectMapper.readValue(value, object : TypeReference<List<DashboardFilterDefinition>>() {})
    }
}
