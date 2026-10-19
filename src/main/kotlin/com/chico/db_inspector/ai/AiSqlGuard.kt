package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class PlanInfo(
    val totalCost: Double?,
    val schemas: Set<String>,
    val relations: Set<String>,
    val plan: Any?,
    /** Expressoes de saida (campo "Output" do EXPLAIN VERBOSE) de todos os nos do plano. */
    val outputs: Set<String> = emptySet()
)

/**
 * Segunda barreira para SQL escrito por IA. A primeira e o usuario somente leitura do banco; esta
 * existe porque em Postgres um SELECT tambem pode ter efeito (nextval, set_config, dblink,
 * pg_terminate_backend...) e porque so certos schemas podem ser lidos.
 *
 * Nao substitui o ReadOnlySqlValidator: roda alem dele.
 */
@Component
class AiSqlGuard(
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties,
    private val objectMapper: ObjectMapper
) {
    companion object {
        /** Funcoes com efeito colateral, acesso a arquivo/rede ou que executam SQL vindo de texto. */
        private val blockedFunctions = listOf(
            "nextval", "setval", "set_config", "pg_sleep\\w*", "pg_notify",
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile",
            "pg_switch_wal", "pg_create_restore_point", "pg_promote", "pg_backup_\\w+", "pg_start_backup",
            "pg_stop_backup", "pg_stat_reset\\w*", "pg_read_\\w+", "pg_ls_\\w+", "pg_stat_file", "pg_file_\\w+",
            "pg_\\w*advisory\\w*", "pg_\\w*replication\\w*", "pg_\\w*_slot\\w*", "pg_logical_\\w+",
            "pg_import_system_collations", "lo_\\w+", "dblink\\w*",
            "\\w*_to_xml\\w*", "\\w*_to_xmlschema"
        )
        private val blockedFunctionRegex =
            Regex("\\b(${blockedFunctions.joinToString("|")})\\s*\\(", RegexOption.IGNORE_CASE)

        /** Alem das palavras do ReadOnlySqlValidator. */
        private val extraWriteKeywords =
            Regex("\\b(merge|copy|call|execute|lock|refresh|reindex|cluster|vacuum|listen|notify)\\b", RegexOption.IGNORE_CASE)
        private val selectIntoRegex = Regex("\\binto\\b", RegexOption.IGNORE_CASE)
        private val rowLockRegex =
            Regex("\\bfor\\s+(update|share|no\\s+key\\s+update|key\\s+share)\\b", RegexOption.IGNORE_CASE)
        private val dollarTagRegex = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$")

        private val functionCallRegex = Regex("\\b([A-Za-z_][A-Za-z0-9_$]*)\\s*\\(")
        private val USER_FUNCTIONS_TTL: Duration = Duration.ofMinutes(10)

        /** U&"\\006Eextval" soletra um nome por escape e escaparia da lista de funcoes. */
        private val unicodeEscapeRegex = Regex("\\bU&['\"]", RegexOption.IGNORE_CASE)
    }

    /** Checagens sem banco. Devolve todos os problemas de uma vez. */
    fun staticProblems(sql: String, caminho: String = "sql"): List<AiProblem> {
        val problems = mutableListOf<AiProblem>()
        ReadOnlySqlValidator.validate(sql)?.let {
            problems += AiProblem(caminho, "somente_leitura", it)
        }
        val scan = scan(sql)
        if (scan.unterminated) {
            problems += AiProblem(caminho, "sql_malformado", "Texto, identificador ou bloco \$\$ sem fechamento")
        }
        unicodeEscapeRegex.find(sql)?.let { match ->
            problems += AiProblem(caminho, "escape_unicode_proibido", "Nao use U&'...' nem U&\"...\"", lineOf(sql, match.range.first))
        }
        scan.commentOffset?.let {
            problems += AiProblem(caminho, "comentario_proibido", "Remova comentarios (-- ou /* */) do SQL", lineOf(sql, it))
        }
        blockedFunctionRegex.findAll(scan.code).forEach { match ->
            problems += AiProblem(
                caminho, "funcao_proibida",
                "A funcao '${match.groupValues[1]}' nao e permitida: tem efeito colateral ou acessa recurso externo",
                lineOf(sql, match.range.first)
            )
        }
        extraWriteKeywords.find(scan.code)?.let { match ->
            problems += AiProblem(caminho, "somente_leitura", "Comando '${match.value}' nao e permitido", lineOf(sql, match.range.first))
        }
        wordRegex.findAll(scan.code).filter { isBlockedColumn(it.value) }.distinctBy { it.value.lowercase() }.forEach { match ->
            problems += AiProblem(
                caminho, "coluna_proibida",
                "A coluna '${match.value}' tem dado sensivel e nao e liberada para IA",
                lineOf(sql, match.range.first)
            )
        }
        selectIntoRegex.find(scan.code)?.let { match ->
            problems += AiProblem(caminho, "select_into_proibido", "SELECT ... INTO cria tabela e nao e permitido", lineOf(sql, match.range.first))
        }
        rowLockRegex.find(scan.code)?.let { match ->
            problems += AiProblem(caminho, "bloqueio_de_linha_proibido", "'${match.value}' bloqueia linhas e nao e permitido", lineOf(sql, match.range.first))
        }
        return problems.distinctBy { listOf(it.regra, it.mensagem, it.linha) }
    }

    fun requireStaticOk(sql: String, caminho: String = "sql") {
        val problems = staticProblems(sql, caminho)
        if (problems.isNotEmpty()) throw AiValidationException(problems)
    }

    @Volatile
    private var userFunctions: Pair<Instant, Set<String>>? = null

    /**
     * Funcao criada no proprio banco (fora de pg_catalog) roda SQL por dentro: o plano mostra so a
     * chamada, nao as tabelas que ela le. Por isso e recusada, salvo as liberadas na configuracao.
     */
    fun functionProblems(sql: String, ctx: UpstreamContext, caminho: String = "sql"): List<AiProblem> {
        val allowed = properties.ai.allowedFunctions.map { it.lowercase() }.toSet()
        val defined = userFunctionNames(ctx, caminho)
        val code = scan(sql).code
        return functionCallRegex.findAll(code)
            .map { it.groupValues[1].lowercase() to it.range.first }
            .filter { (name, _) -> name in defined && name !in allowed }
            .distinctBy { it.first }
            .map { (name, offset) ->
                AiProblem(
                    caminho, "funcao_proibida",
                    "A funcao '$name' e definida no proprio banco e pode ler tabelas fora da lista por dentro; " +
                        "nao e permitida (peca a pessoa para libera-la em dbinspector.ai.allowed-functions)",
                    lineOf(sql, offset)
                )
            }
            .toList()
    }

    /** Nomes das funcoes definidas fora de pg_catalog, em cache. Sem conseguir ler, recusa (fecha). */
    private fun userFunctionNames(ctx: UpstreamContext, caminho: String): Set<String> {
        userFunctions?.takeIf { it.first.isAfter(Instant.now().minus(USER_FUNCTIONS_TTL)) }?.let { return it.second }
        val result = try {
            sqlExecClient.execWithTimeout(
                ctx.endpointUrl, ctx.bearer,
                "SELECT DISTINCT lower(p.proname) AS nome FROM pg_catalog.pg_proc p " +
                    "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                    "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'",
                Duration.ofSeconds(properties.ai.explainTimeoutSeconds)
            )
        } catch (ex: Exception) {
            throw AiValidationException(
                listOf(AiProblem(caminho, "erro_no_banco", "Nao foi possivel conferir as funcoes do banco: ${upstreamErrorMessage(ex)}"))
            )
        }
        // Resposta fora do formato esperado tambem fecha: lista vazia em cache liberaria tudo por 10 min.
        val rows = result["data"] as? List<*>
        val names = rows?.map { row -> (row as? Map<*, *>)?.get("nome")?.toString() }
        if (names == null || names.any { it == null }) {
            throw AiValidationException(
                listOf(AiProblem(caminho, "erro_no_banco", "Resposta inesperada ao conferir as funcoes do banco; tente de novo"))
            )
        }
        val defined = names.filterNotNull().toSet()
        userFunctions = Instant.now() to defined
        return defined
    }

    /** Roda EXPLAIN (sem ANALYZE: nao executa a consulta) e le schemas, tabelas e custo do plano. */
    fun explain(sql: String, ctx: UpstreamContext, caminho: String = "sql"): PlanInfo {
        functionProblems(sql, ctx, caminho).takeIf { it.isNotEmpty() }?.let { throw AiValidationException(it) }
        val query = "EXPLAIN (VERBOSE, FORMAT JSON) ${sql.trim().removeSuffix(";").trim()}"
        val result = try {
            sqlExecClient.execWithTimeout(ctx.endpointUrl, ctx.bearer, query, Duration.ofSeconds(properties.ai.explainTimeoutSeconds))
        } catch (ex: Exception) {
            throw AiValidationException(listOf(AiProblem(caminho, "erro_no_banco", upstreamErrorMessage(ex))))
        }
        return parsePlan(result)
    }

    private val blockedPattern by lazy { Regex(properties.ai.blockedColumnPattern, RegexOption.IGNORE_CASE) }
    private val wordRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val wholeRowRegex = Regex("[A-Za-z0-9_\"]\\.\\*")

    /** Coluna que a IA nunca le (segredo, conta bancaria, contato, nascimento). */
    fun isBlockedColumn(name: String): Boolean {
        val column = name.trim().lowercase()
        return column in properties.ai.blockedColumns.map { it.lowercase() } || blockedPattern.matches(column)
    }

    private fun blockedProblems(plan: PlanInfo, caminho: String): List<AiProblem> {
        val problems = mutableListOf<AiProblem>()
        // Linha inteira aparece como p.* dentro de qualquer expressao: to_jsonb(p.*), (p.*)::text, row_to_json(p.*).
        // count(*) nao tem o ponto. Testado contra o EXPLAIN real do Postgres.
        if (plan.outputs.any { wholeRowRegex.containsMatchIn(it) }) {
            problems += AiProblem(
                caminho, "linha_inteira_proibida",
                "A consulta devolve a linha inteira de uma tabela (to_jsonb(t), row(t), t.*): liste as colunas que precisa"
            )
        }
        plan.outputs.flatMap { wordRegex.findAll(it).map { m -> m.value } }.distinct()
            .filter { isBlockedColumn(it) }
            .forEach { column ->
                problems += AiProblem(
                    caminho, "coluna_proibida",
                    "A coluna '$column' tem dado sensivel e nao e liberada para IA. Com SELECT * ela vem junto: liste as colunas que precisa"
                )
            }
        return problems
    }

    /** Tabela liberada: o schema inteiro esta liberado ou ela foi liberada uma a uma. */
    fun isAllowed(schema: String, table: String): Boolean =
        schema.lowercase() in properties.ai.allowedSchemas.map { it.lowercase() } ||
            "${schema.lowercase()}.${table.lowercase()}" in properties.ai.allowedTables.map { it.lowercase() }

    fun planProblems(plan: PlanInfo, caminho: String = "sql", ignoreCost: Boolean = false): List<AiProblem> {
        val problems = plan.relations
            .filterNot { relation -> isAllowed(relation.substringBefore('.'), relation.substringAfter('.')) }
            .map { relation ->
                AiProblem(
                    caminho, "schema_nao_permitido",
                    "A tabela '$relation' nao esta liberada para IA. Schemas liberados: " +
                        properties.ai.allowedSchemas.sorted().joinToString(", ") +
                        properties.ai.allowedTables.takeIf { it.isNotEmpty() }?.let { "; tabelas: ${it.joinToString(", ")}" }.orEmpty()
                )
            }
            .toMutableList()
        problems += blockedProblems(plan, caminho)
        val cost = plan.totalCost
        if (!ignoreCost && cost != null && cost > properties.ai.maxPlanCost) {
            problems += AiProblem(
                caminho, "custo_acima_do_limite",
                "Custo estimado ${"%.0f".format(cost)} passa do limite ${"%.0f".format(properties.ai.maxPlanCost)}. " +
                    "Restrinja o periodo ou os filtros, ou solicite liberacao ao operador."
            )
        }
        return problems
    }

    /** Estatico + EXPLAIN + schemas + custo. Lanca com a lista completa de problemas. */
    fun check(sql: String, ctx: UpstreamContext, caminho: String = "sql", ignoreCost: Boolean = false): PlanInfo {
        requireStaticOk(sql, caminho)
        val plan = explain(sql, ctx, caminho)
        val problems = planProblems(plan, caminho, ignoreCost)
        if (problems.isNotEmpty()) throw AiValidationException(problems)
        return plan
    }

    internal fun parsePlan(result: Map<String, Any?>): PlanInfo {
        val rows = result["data"] as? List<*> ?: emptyList<Any?>()
        val first = rows.firstOrNull()
        val raw = when (first) {
            is Map<*, *> -> first.values.firstOrNull()
            is List<*> -> first.firstOrNull()
            else -> null
        }
        val plan: Any? = when (raw) {
            is String -> runCatching { objectMapper.readValue<Any>(raw) }.getOrNull()
            else -> raw
        }
        val schemas = mutableSetOf<String>()
        val relations = mutableSetOf<String>()
        val outputs = mutableSetOf<String>()
        var totalCost: Double? = null

        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    // So no com tabela: Function Scan tambem traz "Schema" (pg_catalog de generate_series).
                    val relation = node["Relation Name"] as? String
                    if (relation != null) {
                        val schema = node["Schema"] as? String ?: "?"
                        schemas += schema
                        relations += "$schema.$relation"
                    }
                    (node["Output"] as? List<*>)?.forEach { entry -> (entry as? String)?.let { outputs += it } }
                    node.values.forEach { walk(it) }
                }
                is List<*> -> node.forEach { walk(it) }
            }
        }
        walk(plan)
        val top = (plan as? List<*>)?.firstOrNull() as? Map<*, *>
        val topPlan = top?.get("Plan") as? Map<*, *>
        totalCost = (topPlan?.get("Total Cost") as? Number)?.toDouble()
        return PlanInfo(totalCost = totalCost, schemas = schemas, relations = relations, plan = plan, outputs = outputs)
    }

    private data class Scan(val code: String, val commentOffset: Int?, val unterminated: Boolean)

    /**
     * Apaga o conteudo de textos ('...', E'...', $tag$...$tag$) mantendo as posicoes, tira as aspas
     * de identificadores ("nextval" vira nextval, para nao escapar da lista de funcoes) e acha o
     * primeiro comentario.
     */
    private fun scan(sql: String): Scan {
        val out = StringBuilder(sql.length)
        var i = 0
        var commentOffset: Int? = null
        var unterminated = false
        fun blank(c: Char) = if (c == '\n') '\n' else ' '

        while (i < sql.length) {
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            when {
                c == '-' && next == '-' || c == '/' && next == '*' -> {
                    if (commentOffset == null) commentOffset = i
                    out.append(c)
                    i++
                }
                c == '\'' -> {
                    val escapes = i > 0 && (sql[i - 1] == 'E' || sql[i - 1] == 'e') &&
                        (i < 2 || !sql[i - 2].isLetterOrDigit() && sql[i - 2] != '_')
                    out.append('\'')
                    i++
                    var closed = false
                    while (i < sql.length) {
                        val ch = sql[i]
                        if (escapes && ch == '\\' && i + 1 < sql.length) {
                            out.append(' ').append(blank(sql[i + 1]))
                            i += 2
                            continue
                        }
                        if (ch == '\'') {
                            if (sql.getOrNull(i + 1) == '\'') {
                                out.append("  ")
                                i += 2
                                continue
                            }
                            out.append('\'')
                            i++
                            closed = true
                            break
                        }
                        out.append(blank(ch))
                        i++
                    }
                    if (!closed) unterminated = true
                }
                c == '"' -> {
                    out.append(' ')
                    i++
                    var closed = false
                    while (i < sql.length) {
                        val ch = sql[i]
                        if (ch == '"') {
                            if (sql.getOrNull(i + 1) == '"') {
                                out.append("\"\"")
                                i += 2
                                continue
                            }
                            out.append(' ')
                            i++
                            closed = true
                            break
                        }
                        out.append(ch)
                        i++
                    }
                    if (!closed) unterminated = true
                }
                c == '$' && (i == 0 || !sql[i - 1].isLetterOrDigit() && sql[i - 1] != '_') -> {
                    val tag = dollarTagRegex.matchAt(sql, i)
                    if (tag == null) {
                        out.append(c)
                        i++
                    } else {
                        val delimiter = tag.value
                        val end = sql.indexOf(delimiter, i + delimiter.length)
                        if (end < 0) {
                            unterminated = true
                            sql.substring(i).forEach { out.append(blank(it)) }
                            i = sql.length
                        } else {
                            out.append('\'')
                            for (k in i + 1 until end + delimiter.length - 1) out.append(blank(sql[k]))
                            out.append('\'')
                            i = end + delimiter.length
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return Scan(out.toString(), commentOffset, unterminated)
    }

    private fun lineOf(sql: String, offset: Int): Int = sql.take(offset).count { it == '\n' } + 1
}

/** Mensagem curta do erro do upstream sql/exec, sem stack trace nem cabecalhos HTTP. */
internal fun upstreamErrorMessage(ex: Exception): String {
    val raw = ex.message ?: return "Falha ao executar no banco"
    // O upstream devolve uma pagina HTML em erro 500: nao ha mensagem do Postgres para repassar.
    if (raw.contains("<h1>") || raw.contains("<html", ignoreCase = true)) {
        return "O banco devolveu erro 500 sem detalhe: confira nomes de coluna e tipos na consulta"
    }
    val body = raw.substringAfter("body=", raw).trim()
    val fromJson = Regex("\"(?:detail|message|error|erro|mensagem)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(body)?.groupValues?.get(1)
    return (fromJson ?: body).replace("\\n", " ").replace("\\\"", "\"").take(500)
}
