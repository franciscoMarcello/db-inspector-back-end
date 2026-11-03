package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class AiCatalogColumn(val nome: String, val tipo: String, val obrigatorio: Boolean, val comentario: String? = null)

data class AiCatalogForeignKey(val coluna: String, val referencia: String, val liberada: Boolean)

data class AiCatalogIncomingKey(val origem: String, val coluna: String, val liberada: Boolean)

data class AiCatalogTable(
    val schema: String,
    val nome: String,
    val comentario: String?,
    val pk: List<String>,
    val colunas: List<AiCatalogColumn>,
    val fks: List<AiCatalogForeignKey>
)

data class AiSchemaSummary(val schema: String, val tabelas: Int)

data class AiTableMatch(val tabela: String, val colunasEncontradas: List<String>, val totalColunas: Int)

data class AiTableDescription(
    val tabela: String,
    val comentario: String?,
    val pk: List<String>,
    val colunas: List<AiCatalogColumn>,
    val referencia: List<AiCatalogForeignKey>,
    val referenciadaPor: List<AiCatalogIncomingKey>,
    val linhasEstimadas: Long?,
    val colunasNovasNoBanco: List<String>,
    val colunasRemovidasDoBanco: List<String>,
    val fonte: String
)

/**
 * Estrutura do banco para a IA, sem despejar tudo no contexto: listar schemas, buscar tabelas por
 * termo, descrever uma tabela e seus relacionamentos. O catalogo vem de catalogo.json em
 * dbinspector.ai.context-path (fora do git; gerado por scripts/gerar_catalogo_ia.py); a descricao
 * confere as colunas ao vivo no banco. Sem o arquivo, o catalogo fica vazio e so a consulta ao vivo vale.
 */
@Service
class AiCatalogService(
    private val guard: AiSqlGuard,
    private val sqlExecClient: SqlExecClient,
    objectMapper: ObjectMapper,
    properties: DbInspectorProperties
) {
    private data class RawCatalog(val geradoEm: String?, val tabelas: List<RawTable>)
    private data class RawTable(
        val schema: String,
        val nome: String,
        val comentario: String? = null,
        val pk: List<String> = emptyList(),
        val colunas: List<List<Any?>> = emptyList(),
        val fks: List<List<String>> = emptyList()
    )

    private val identifierRegex = Regex("^[a-z_][a-z0-9_]*$")
    private val generatedAt: String?
    private val rawBytes: ByteArray?
    private val tables: Map<String, AiCatalogTable>
    private val incoming: Map<String, List<Pair<String, String>>>

    init {
        val file = properties.ai.contextPath.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it, CATALOG_FILE) }
        val raw: RawCatalog = (
            file?.takeIf { Files.isRegularFile(it) }?.let { Files.newInputStream(it) }
                ?: javaClass.classLoader.getResourceAsStream("ai/$CATALOG_FILE")
            )?.use { objectMapper.readValue<RawCatalog>(it) } ?: RawCatalog(null, emptyList())
        rawBytes = file?.takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
            ?: javaClass.classLoader.getResourceAsStream("ai/$CATALOG_FILE")?.use { it.readBytes() }
        generatedAt = raw.geradoEm
        tables = raw.tabelas.associate { table ->
            "${table.schema}.${table.nome}" to AiCatalogTable(
                schema = table.schema,
                nome = table.nome,
                comentario = table.comentario,
                pk = table.pk,
                // Coluna sensivel nem aparece no catalogo: a IA nao descobre que ela existe.
                colunas = table.colunas.filterNot { column -> guard.isBlockedColumn(column[0].toString()) }.map { column ->
                    AiCatalogColumn(
                        nome = column[0].toString(),
                        tipo = column[1].toString(),
                        obrigatorio = column.getOrNull(2) == true,
                        comentario = column.getOrNull(3)?.toString()
                    )
                },
                fks = table.fks.map { fk ->
                    AiCatalogForeignKey(coluna = fk[0], referencia = "${fk[1]}.${fk[2]}.${fk[3]}", liberada = false)
                }
            )
        }
        incoming = raw.tabelas
            .flatMap { table -> table.fks.map { fk -> "${fk[1]}.${fk[2]}" to ("${table.schema}.${table.nome}" to fk[0]) } }
            .groupBy({ it.first }, { it.second })
    }

    fun generatedAt(): String? = generatedAt

    /** Arquivo do catalogo como esta em disco, para o pacote de instrucoes. Nulo se nao houver. */
    fun catalogFile(): ByteArray? = rawBytes

    private companion object {
        const val CATALOG_FILE = "catalogo.json"
    }

    fun schemas(): List<AiSchemaSummary> =
        visibleTables().groupBy { it.schema }
            .map { (schema, list) -> AiSchemaSummary(schema, list.size) }
            .sortedBy { it.schema }

    /** Busca por nome de tabela ou de coluna. Tabela que casa no nome vem antes. */
    fun search(term: String, schema: String?, limit: Int = 30): List<AiTableMatch> {
        val words = term.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        require(words.isNotEmpty()) { "Informe um termo de busca com ao menos 2 letras" }
        val schemaFilter = schema?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return visibleTables()
            .filter { schemaFilter == null || it.schema == schemaFilter }
            .mapNotNull { table ->
                val nameHits = words.count { table.nome.contains(it) }
                val columnHits = table.colunas.filter { column -> words.any { column.nome.contains(it) } }.map { it.nome }
                val score = nameHits * 10 + columnHits.size
                if (score == 0) null else Triple(table, columnHits, score)
            }
            .sortedWith(compareByDescending<Triple<AiCatalogTable, List<String>, Int>> { it.third }.thenBy { it.first.nome.length })
            .take(limit.coerceIn(1, 100))
            .map { (table, columns, _) -> AiTableMatch("${table.schema}.${table.nome}", columns.take(15), table.colunas.size) }
    }

    /** Descreve uma tabela: catalogo + conferencia ao vivo das colunas e linhas estimadas. */
    fun describe(schema: String, table: String, ctx: UpstreamContext?): AiTableDescription {
        val schemaName = schema.trim().lowercase()
        val tableName = table.trim().lowercase()
        if (!identifierRegex.matches(schemaName) || !identifierRegex.matches(tableName)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome de schema ou tabela invalido")
        }
        if (!guard.isAllowed(schemaName, tableName)) {
            throw AiRuleException(
                HttpStatus.FORBIDDEN, "schema_nao_permitido", "A tabela '$schemaName.$tableName' nao esta liberada para IA"
            )
        }
        val key = "$schemaName.$tableName"
        val cataloged = tables[key]
        val live = ctx?.let { liveColumns(schemaName, tableName, it) }
        if (cataloged == null && live?.columns.isNullOrEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tabela '$key' nao encontrada")
        }
        val liveNames = live?.columns?.map { it.nome }?.toSet()
        val catalogNames = cataloged?.colunas?.map { it.nome }?.toSet().orEmpty()
        return AiTableDescription(
            tabela = key,
            comentario = cataloged?.comentario,
            pk = cataloged?.pk.orEmpty(),
            colunas = live?.columns?.takeIf { it.isNotEmpty() }
                ?.map { liveColumn -> cataloged?.colunas?.firstOrNull { it.nome == liveColumn.nome } ?: liveColumn }
                ?: cataloged?.colunas.orEmpty(),
            referencia = cataloged?.fks.orEmpty().map { fk ->
                fk.copy(liberada = fk.referencia.split('.').let { guard.isAllowed(it[0], it[1]) })
            },
            referenciadaPor = incoming[key].orEmpty().map { (origin, column) ->
                AiCatalogIncomingKey(origin, column, origin.split('.').let { guard.isAllowed(it[0], it[1]) })
            }.sortedBy { it.origem },
            linhasEstimadas = live?.estimatedRows,
            colunasNovasNoBanco = liveNames?.let { (it - catalogNames).sorted() }.orEmpty(),
            colunasRemovidasDoBanco = liveNames?.let { (catalogNames - it).sorted() }.orEmpty(),
            fonte = when {
                live == null -> "catalogo de $generatedAt"
                cataloged == null -> "banco ao vivo (fora do catalogo)"
                else -> "catalogo de $generatedAt conferido ao vivo"
            }
        )
    }

    private data class LiveTable(val columns: List<AiCatalogColumn>, val estimatedRows: Long?)

    /** Consulta propria do servidor (nomes ja validados), nao SQL da IA. */
    private fun liveColumns(schema: String, table: String, ctx: UpstreamContext): LiveTable? = runCatching {
        val columnsResult = sqlExecClient.execWithTimeout(
            ctx.endpointUrl, ctx.bearer,
            """
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = '$schema' AND table_name = '$table'
            ORDER BY ordinal_position
            """.trimIndent(),
            Duration.ofSeconds(15)
        )
        val estimateResult = sqlExecClient.execWithTimeout(
            ctx.endpointUrl, ctx.bearer,
            """
            SELECT c.reltuples::bigint AS linhas
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = '$schema' AND c.relname = '$table'
            """.trimIndent(),
            Duration.ofSeconds(15)
        )
        val columns = (columnsResult["data"] as? List<*>).orEmpty().mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val name = map["column_name"]?.toString() ?: return@mapNotNull null
            if (guard.isBlockedColumn(name)) return@mapNotNull null
            AiCatalogColumn(
                nome = name,
                tipo = map["data_type"]?.toString() ?: "",
                obrigatorio = map["is_nullable"]?.toString().equals("NO", ignoreCase = true)
            )
        }
        val estimate = ((estimateResult["data"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("linhas")
        LiveTable(columns, (estimate as? Number)?.toLong()?.takeIf { it >= 0 })
    }.getOrNull()

    private fun visibleTables(): List<AiCatalogTable> = tables.values.filter { guard.isAllowed(it.schema, it.nome) }
}
