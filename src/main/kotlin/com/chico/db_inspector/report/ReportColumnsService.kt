package com.chico.dbinspector.report

import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class ReportColumnsService(
    private val sqlExecClient: SqlExecClient,
    private val hanaQueryService: HanaQueryService,
    private val queryService: ReportQueryService,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    companion object {
        private const val COLUMNS_CACHE_TTL_SECONDS = 900L
        private const val COLUMNS_QUERY_TIMEOUT_SECONDS = 3
    }

    private val log = LoggerFactory.getLogger(ReportColumnsService::class.java)
    private val columnsCache = ConcurrentHashMap<String, CachedColumns>()

    fun columns(
        entity: ReportEntity,
        reportId: UUID,
        ctx: UpstreamContext,
        source: String,
        includeTypes: Boolean,
        refresh: Boolean
    ): ReportColumnsResponse {
        val normalizedSource = normalizeColumnsSource(source)
        val cacheKey = columnsCacheKey(entity, normalizedSource, includeTypes)
        val now = Instant.now(clock)
        val cached = columnsCache[cacheKey]
        if (!refresh && cached != null && cached.expiresAt.isAfter(now)) {
            log.info("report_columns reportId={} source={} elapsedMs={} cacheHit={}", reportId, normalizedSource, 0, true)
            return cached.response.copy(
                generatedAt = now.toString(),
                cache = ReportColumnsCacheInfo(hit = true, ttlSeconds = COLUMNS_CACHE_TTL_SECONDS)
            )
        }

        val startedAt = System.nanoTime()
        val sources = linkedMapOf<String, ReportColumnsSourceResponse>()
        if (normalizedSource == "primary" || normalizedSource == "both") {
            sources["primary"] = ReportColumnsSourceResponse(
                label = "Agromobi",
                columns = extractPrimaryColumns(entity, ctx, includeTypes)
            )
        }
        if (normalizedSource == "secondary" || normalizedSource == "both") {
            val secondSql = entity.secondSql?.trim().takeUnless { it.isNullOrBlank() }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Relatorio sem SQL secundario configurado")
            sources["secondary"] = ReportColumnsSourceResponse(
                label = "SAP HANA",
                columns = extractSecondaryColumns(entity, secondSql, includeTypes)
            )
        }

        val mergedColumns = sources.values
            .flatMap { it.columns.map { col -> col.name } }
            .distinct()

        val response = ReportColumnsResponse(
            reportId = reportId.toString(),
            generatedAt = now.toString(),
            cache = ReportColumnsCacheInfo(hit = false, ttlSeconds = COLUMNS_CACHE_TTL_SECONDS),
            sources = sources,
            mergedColumns = mergedColumns
        )

        columnsCache[cacheKey] = CachedColumns(response = response, expiresAt = now.plusSeconds(COLUMNS_CACHE_TTL_SECONDS))

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        log.info("report_columns reportId={} source={} elapsedMs={} cacheHit={}", reportId, normalizedSource, elapsedMs, false)
        return response
    }

    private fun extractPrimaryColumns(entity: ReportEntity, ctx: UpstreamContext, includeTypes: Boolean): List<ReportColumnMeta> {
        val queryTemplate = entity.sql.trim()
        if (queryTemplate.isBlank()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SQL primaria invalida para extracao de metadados")
        }
        val renderedQuery = queryService.buildQueryWithParams(queryTemplate, entity.variables, emptyMap(), enforceRequired = false)
        val metadataQuery = "SELECT * FROM (${renderedQuery.trim().trimEnd(';')}) dbi_primary_meta WHERE 1 = 0"
        return try {
            val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, metadataQuery, true, true)
            val description = result["description"] as? List<*>
            val columns = description?.mapNotNull { raw ->
                val column = raw as? Map<*, *> ?: return@mapNotNull null
                val name = (column["name"] ?: column["column_name"] ?: column["columnName"] ?: column["field"])?.toString()
                    ?: return@mapNotNull null
                val rawType = (column["type"] ?: column["data_type"] ?: column["type_name"])?.toString()
                ReportColumnMeta(name = name, type = if (includeTypes) normalizeColumnType(rawType) else null)
            }.orEmpty()
            columns.distinctBy { it.name }
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SQL primaria invalida para extracao de metadados")
        } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Falha ao extrair metadados da origem primaria")
        }
    }

    private fun extractSecondaryColumns(entity: ReportEntity, secondSql: String, includeTypes: Boolean): List<ReportColumnMeta> {
        val renderedQuery = queryService.buildQueryWithParams(secondSql, entity.variables, emptyMap(), enforceRequired = false)
        return try {
            hanaQueryService.extractColumns(renderedQuery, COLUMNS_QUERY_TIMEOUT_SECONDS).map {
                ReportColumnMeta(name = it.name, type = if (includeTypes) it.type else null)
            }
        } catch (ex: ResponseStatusException) {
            if (ex.statusCode == HttpStatus.SERVICE_UNAVAILABLE) throw ex
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SQL secundaria invalida para extracao de metadados")
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SQL secundaria invalida para extracao de metadados")
        }
    }

    private fun normalizeColumnsSource(source: String): String {
        val value = source.trim().lowercase()
        if (value !in setOf("primary", "secondary", "both")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'source' invalido")
        }
        return value
    }

    private fun columnsCacheKey(entity: ReportEntity, source: String, includeTypes: Boolean): String {
        val sqlHash = sha256Hex("${entity.sql}|${entity.secondSql.orEmpty()}")
        return "${entity.id}:$source:$includeTypes:$sqlHash"
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalizeColumnType(rawType: String?): String {
        val value = rawType?.trim()?.uppercase().orEmpty()
        return when {
            value.isBlank() -> "unknown"
            value.contains("BOOL") || value == "BIT" -> "boolean"
            value.contains("TIMESTAMP") || value.contains("TIME") -> "datetime"
            value.contains("DATE") -> "date"
            value.contains("INT") || value.contains("DEC") || value.contains("NUM") || value.contains("FLOAT") || value.contains("DOUBLE") -> "number"
            else -> "string"
        }
    }

    private data class CachedColumns(
        val response: ReportColumnsResponse,
        val expiresAt: Instant
    )
}
