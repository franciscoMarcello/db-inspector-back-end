package com.chico.dbinspector.service

import com.chico.dbinspector.util.HanaReadOnlySqlValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Types

@Service
class HanaQueryService(
    @Qualifier("hanaJdbcTemplate")
    @Autowired(required = false)
    private val jdbcTemplate: JdbcTemplate?
) {
    private val log = LoggerFactory.getLogger(HanaQueryService::class.java)

    data class QueryResult(
        val columns: List<String>,
        val rows: List<Map<String, Any?>>
    )

    data class ColumnMetadata(
        val name: String,
        val type: String
    )

    /**
     * Telas de pessoa (/compare, teste de conexao, e-mail). Barra o que tem efeito colateral dentro de um SELECT
     * no HANA e roda em transacao sempre desfeita. Linhas como o queryForList de antes (chave sem diferenciar
     * maiusculas), sem limite de linhas nem timeout, para nao mudar relatorio e e-mail existentes.
     */
    fun exec(sql: String): QueryResult {
        HanaReadOnlySqlValidator.sideEffectProblem(sql)?.let { throw IllegalArgumentException(it) }
        val template = requireTemplate()
        return try {
            template.execute(ConnectionCallback { con ->
                inRolledBackTransaction(con) {
                    con.prepareStatement(sql).use { ps ->
                        ps.executeQuery().use { rs ->
                            val mapper = ColumnMapRowMapper()
                            val rows = mutableListOf<Map<String, Any?>>()
                            while (rs.next()) rows += mapper.mapRow(rs, rows.size)
                            QueryResult(labels(rs), rows)
                        }
                    }
                }
            }) ?: QueryResult(emptyList(), emptyList())
        } catch (ex: DataAccessException) {
            log.error("Erro ao executar query no SAP HANA: {}", ex.message)
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Erro ao executar query no SAP HANA"
            )
        }
    }

    /**
     * Consulta da IA: validador mais rigido do HANA (somente leitura, sem senha/chave/token, sem `*`), corte em
     * [maxRows] e [timeoutSeconds] no proprio statement, transacao sempre desfeita e coluna sensivel mascarada.
     * O erro volta limpo para a IA corrigir o SQL, sem endereco de conexao.
     */
    fun execLimited(sql: String, maxRows: Int, timeoutSeconds: Int): QueryResult {
        HanaReadOnlySqlValidator.requireReadOnly(sql)
        val template = requireTemplate()
        return try {
            template.execute(ConnectionCallback { con ->
                inRolledBackTransaction(con) {
                    con.prepareStatement(sql).use { ps ->
                        ps.maxRows = maxRows
                        ps.queryTimeout = timeoutSeconds
                        ps.executeQuery().use { rs ->
                            val columns = labels(rs)
                            val masked = columns.map { HanaReadOnlySqlValidator.isSensitiveColumn(it) }
                            val rows = mutableListOf<Map<String, Any?>>()
                            while (rs.next()) {
                                rows += columns.withIndex().associate { (i, name) ->
                                    name to if (masked[i]) "***" else readable(rs.getObject(i + 1))
                                }
                            }
                            QueryResult(columns, rows)
                        }
                    }
                }
            }) ?: QueryResult(emptyList(), emptyList())
        } catch (ex: DataAccessException) {
            log.warn("Erro na consulta da IA no SAP HANA: {}", ex.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, aiErrorMessage(ex))
        }
    }

    private fun requireTemplate(): JdbcTemplate = jdbcTemplate ?: throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Conexao com SAP HANA nao configurada"
    )

    private fun <T> inRolledBackTransaction(con: Connection, block: () -> T): T {
        val autoCommit = con.autoCommit
        con.autoCommit = false
        try {
            return block()
        } finally {
            con.rollback()
            con.autoCommit = autoCommit
        }
    }

    private fun labels(rs: ResultSet): List<String> = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }

    /**
     * Erro de SQL (sintaxe, coluna, tipo) volta com o texto do HANA para a IA corrigir. Tabela inexistente e
     * falta de permissao viram a mesma mensagem, para nao revelar o que existe; falha de conexao nao mostra nada.
     */
    private fun aiErrorMessage(ex: DataAccessException): String {
        val cause = ex.mostSpecificCause
        val code = (cause as? SQLException)?.errorCode ?: 0
        return when {
            cause is SQLTimeoutException -> "Consulta passou do tempo limite. Filtre mais ou agregue."
            code in NOT_FOUND_OR_DENIED -> "Tabela, schema ou objeto inexistente, ou sem permissao de leitura"
            code in 1..9_999 -> "Erro no SAP HANA [$code]: " + cleanMessage(cause.message)
            else -> "Erro no SAP HANA (conexao ou servidor). Tente de novo; se repetir, avise a pessoa."
        }
    }

    private fun cleanMessage(message: String?): String = message.orEmpty()
        .replace(Regex("^SAP DBTech JDBC: \\[\\d+]:\\s*"), "")
        .replace(Regex("jdbc:\\S+"), "[conexao]")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "[endereco]")
        .take(300)

    // NCLOB/BLOB do B1 nao viram JSON; texto longo e cortado.
    private fun readable(value: Any?): Any? = when (value) {
        is java.sql.Clob -> value.getSubString(1, minOf(value.length(), 4_000L).toInt())
        is java.sql.Blob -> "[binario ${value.length()} bytes]"
        is ByteArray -> "[binario ${value.size} bytes]"
        else -> value
    }

    fun extractColumns(sql: String, timeoutSeconds: Int = 3): List<ColumnMetadata> {
        if (jdbcTemplate == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Conexao com SAP HANA nao configurada"
            )
        }
        HanaReadOnlySqlValidator.sideEffectProblem(sql)?.let { throw IllegalArgumentException(it) }
        val metadataQuery = "SELECT * FROM (${sql.trim().trimEnd(';')}) dbi_hana_meta WHERE 1 = 0"
        return try {
            jdbcTemplate.execute(
                PreparedStatementCreator { con ->
                    val ps = con.prepareStatement(metadataQuery)
                    ps.queryTimeout = timeoutSeconds
                    ps
                },
                PreparedStatementCallback { ps ->
                    ps.executeQuery().use { rs ->
                        val meta = rs.metaData
                        (1..meta.columnCount).map { idx ->
                            ColumnMetadata(
                                name = meta.getColumnLabel(idx),
                                type = normalizeSqlType(meta.getColumnType(idx), meta.getColumnTypeName(idx))
                            )
                        }
                    }
                }
            ) ?: emptyList()
        } catch (ex: DataAccessException) {
            log.error("Erro ao extrair metadados no SAP HANA: {}", ex.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Origem secundaria indisponivel")
        }
    }

    private fun normalizeSqlType(sqlType: Int, typeName: String?): String = when (sqlType) {
        Types.BIGINT, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.INTEGER, Types.NUMERIC, Types.REAL, Types.SMALLINT, Types.TINYINT -> "number"
        Types.DATE -> "date"
        Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "datetime"
        Types.BOOLEAN, Types.BIT -> "boolean"
        else -> {
            val raw = typeName?.uppercase().orEmpty()
            when {
                raw.contains("DATE") -> "date"
                raw.contains("TIME") -> "datetime"
                raw.contains("INT") || raw.contains("DEC") || raw.contains("NUM") || raw.contains("DOUBLE") -> "number"
                raw.contains("BOOL") -> "boolean"
                raw.isBlank() -> "unknown"
                else -> "string"
            }
        }
    }

    private companion object {
        /** 258 sem privilegio, 259 tabela inexistente, 362 schema inexistente. */
        val NOT_FOUND_OR_DENIED = setOf(258, 259, 362)
    }
}
