package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Consultas exploratorias: a IA testa hipoteses (distribuicao de status, JOIN que multiplica
 * linha, FK nula, total contra referencia) antes de criar o relatorio. Nada e gravado.
 */
@Service
class AiQueryService(
    private val guard: AiSqlGuard,
    private val limiter: AiQueryLimiter,
    private val approvals: AiApprovalService,
    private val sqlExecClient: SqlExecClient,
    private val auditService: AdminAuditService,
    private val properties: DbInspectorProperties
) {
    fun query(request: AiQueryRequest, ctx: UpstreamContext): AiQueryResponse {
        val sql = request.sql.trim().removeSuffix(";").trim()
        guard.requireStaticOk(sql)
        val token = currentAiToken()
        val approval = token?.let { approvals.findActive(it.tokenId, sql) }
        val maxRows = approval?.approvedRows ?: properties.ai.queryMaxRows
        val requested = request.limite ?: properties.ai.queryMaxRows
        val limit = requested.coerceIn(1, maxRows)

        return limiter.run(token?.tokenId) {
            val plan = guard.explain(sql, ctx)
            val problems = guard.planProblems(plan, ignoreCost = approval != null)
            if (problems.isNotEmpty()) throw AiValidationException(problems)

            // Uma linha a mais so para saber se cortou.
            val wrapped = "SELECT * FROM ($sql) ai_consulta LIMIT ${limit + 1}"
            val start = System.nanoTime()
            val result = try {
                sqlExecClient.execWithTimeout(
                    ctx.endpointUrl, ctx.bearer, wrapped, Duration.ofSeconds(properties.ai.queryTimeoutSeconds)
                )
            } catch (ex: Exception) {
                throw AiValidationException(listOf(AiProblem("sql", "erro_no_banco", upstreamErrorMessage(ex))))
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val (columns, rows) = tabularResult(result)
            val truncated = rows.size > limit

            auditService.log(
                action = "AI_QUERY",
                targetType = "AI_TOKEN",
                targetId = token?.tokenId?.toString(),
                details = mapOf(
                    "token" to token?.tokenName,
                    "sql" to sql.take(4_000),
                    "rows" to minOf(rows.size, limit),
                    "elapsedMs" to elapsedMs,
                    "cost" to plan.totalCost,
                    "approvalId" to approval?.id?.toString()
                )
            )

            AiQueryResponse(
                colunas = columns,
                linhas = rows.take(limit),
                quantidade = minOf(rows.size, limit),
                truncado = truncated,
                limiteAplicado = limit,
                custoEstimado = plan.totalCost,
                tempoMs = elapsedMs,
                liberacaoId = approval?.id?.toString(),
                aviso = when {
                    requested > maxRows -> "Pedido de $requested linhas reduzido para $maxRows. Para mais, solicite liberacao."
                    truncated -> "Resultado cortado em $limit linhas. Prefira agregar (GROUP BY) a trazer linhas."
                    else -> null
                }
            )
        }
    }

    fun explain(request: AiExplainRequest, ctx: UpstreamContext): AiExplainResponse {
        val sql = request.sql.trim().removeSuffix(";").trim()
        guard.requireStaticOk(sql)
        val plan = limiter.run(currentAiToken()?.tokenId) { guard.explain(sql, ctx) }
        return AiExplainResponse(
            custoEstimado = plan.totalCost,
            limiteDeCusto = properties.ai.maxPlanCost,
            schemas = plan.schemas,
            tabelas = plan.relations,
            problemas = guard.planProblems(plan),
            plano = plan.plan
        )
    }

}

/**
 * Colunas e linhas do retorno do sql/exec. Sem linhas, as colunas vem de `fields` (formato do
 * upstream: [{"name": "a", "type": ...}]) ou `description`.
 */
internal fun tabularResult(result: Map<String, Any?>): Pair<List<String>, List<Map<String, Any?>>> {
    val data = result["data"] as? List<*> ?: emptyList<Any?>()
    val describedColumns = ((result["fields"] ?: result["description"]) as? List<*>)?.mapNotNull { column ->
        when (column) {
            is Map<*, *> -> (column["name"] ?: column["column_name"] ?: column["columnName"] ?: column["field"])?.toString()
            is List<*> -> column.firstOrNull()?.toString()
            else -> null
        }
    }.orEmpty()
    val columns = when (val firstRow = data.firstOrNull()) {
        is Map<*, *> -> firstRow.keys.map { it.toString() }
        else -> describedColumns
    }.ifEmpty { describedColumns }

    val rows = data.mapNotNull { row ->
        when (row) {
            is Map<*, *> -> columns.associateWith { row[it] }
            is List<*> -> columns.mapIndexed { index, column -> column to row.getOrNull(index) }.toMap()
            else -> null
        }
    }
    return columns to rows
}
