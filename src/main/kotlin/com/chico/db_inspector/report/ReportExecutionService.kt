package com.chico.dbinspector.report

import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger

data class ExecutionResult(
    val query: String,
    val columns: List<String>,
    val allRows: List<Map<String, Any?>>, 
    val elapsedMs: Long
)

data class PaginatedExecutionResult(
    val query: String,
    val columns: List<String>,
    val rows: List<Map<String, Any?>>, 
    val rowCount: Int,
    val elapsedMs: Long
)

@Service
class ReportExecutionService(
    private val sqlExecClient: SqlExecClient,
    private val queryService: ReportQueryService
) {
    fun executeReport(entity: ReportEntity, ctx: UpstreamContext, params: Map<String, Any?>): ExecutionResult {
        val queryTemplate = entity.sql.trim()
        require(queryTemplate.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(queryTemplate)
        val query = queryService.buildQueryWithParams(queryTemplate, entity.variables, params)

        val start = System.nanoTime()
        val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val tabular = EmailReportFormatter.toTabular(result)
        val columns = tabular?.columns ?: emptyList()
        val allRows = tabular?.rows?.map { row ->
            columns.zip(row).associate { (column, value) -> column to value }
        } ?: emptyList()

        return ExecutionResult(query = query, columns = columns, allRows = allRows, elapsedMs = elapsedMs)
    }

    fun executeReportPage(
        entity: ReportEntity,
        ctx: UpstreamContext,
        params: Map<String, Any?>,
        page: Int,
        size: Int
    ): PaginatedExecutionResult {
        val queryTemplate = entity.sql.trim()
        require(queryTemplate.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(queryTemplate)
        val query = queryService.buildQueryWithParams(queryTemplate, entity.variables, params)
        val paginatedQuery = toPaginatedSelect(query, size, page * size)
        val countQuery = toCountSelect(query)

        val start = System.nanoTime()
        val pageResult = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, paginatedQuery, true, true)
        val totalRows = fetchTotalRows(ctx, countQuery)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val tabular = EmailReportFormatter.toTabular(pageResult)
        val columns = tabular?.columns ?: emptyList()
        val rows = tabular?.rows?.map { row ->
            columns.zip(row).associate { (column, value) -> column to value }
        } ?: emptyList()

        return PaginatedExecutionResult(
            query = paginatedQuery,
            columns = columns,
            rows = rows,
            rowCount = totalRows,
            elapsedMs = elapsedMs
        )
    }

    private fun fetchTotalRows(ctx: UpstreamContext, countQuery: String): Int {
        val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, countQuery, true, true)
        val tabular = EmailReportFormatter.toTabular(result)
        val firstValue = tabular?.rows?.firstOrNull()?.firstOrNull()
            ?: throw IllegalStateException("Resposta de count sem dados")

        return when (firstValue) {
            is Int -> firstValue
            is Long -> firstValue.toInt()
            is Short -> firstValue.toInt()
            is Byte -> firstValue.toInt()
            is BigInteger -> firstValue.toInt()
            is BigDecimal -> firstValue.toInt()
            is Number -> firstValue.toInt()
            is String -> firstValue.trim().toIntOrNull()
                ?: throw IllegalStateException("Valor de count invalido: '$firstValue'")
            else -> throw IllegalStateException("Tipo de count invalido: ${firstValue::class.java.name}")
        }
    }

    private fun toPaginatedSelect(rawQuery: String, limit: Int, offset: Int): String {
        val query = rawQuery.trim().trimEnd(';').trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        val startsLikeReadOnly = query.startsWith("select", ignoreCase = true) ||
            query.startsWith("with", ignoreCase = true)
        require(startsLikeReadOnly) { "Paginacao disponivel apenas para SELECT/WITH" }
        return "SELECT * FROM ($query) dbi_paginated LIMIT $limit OFFSET $offset"
    }

    private fun toCountSelect(rawQuery: String): String {
        val query = rawQuery.trim().trimEnd(';').trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        val startsLikeReadOnly = query.startsWith("select", ignoreCase = true) ||
            query.startsWith("with", ignoreCase = true)
        require(startsLikeReadOnly) { "Paginacao disponivel apenas para SELECT/WITH" }
        return "SELECT COUNT(*) AS dbi_total_rows FROM ($query) dbi_counted"
    }
}
