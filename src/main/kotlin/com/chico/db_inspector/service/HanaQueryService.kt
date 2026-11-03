package com.chico.dbinspector.service

import com.chico.dbinspector.util.HanaReadOnlySqlValidator
import com.chico.dbinspector.util.ReadOnlySqlValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
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

    fun exec(sql: String): QueryResult {
        if (jdbcTemplate == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Conexao com SAP HANA nao configurada"
            )
        }
        ReadOnlySqlValidator.requireReadOnly(sql)
        return try {
            val rawRows: List<Map<String, Any?>> = jdbcTemplate.queryForList(sql)
                .map { row -> row.mapValues { (_, v) -> v } }
            val columns = rawRows.firstOrNull()?.keys?.toList() ?: emptyList()
            QueryResult(columns = columns, rows = rawRows)
        } catch (ex: DataAccessException) {
            log.error("Erro ao executar query no SAP HANA: {}", ex.message)
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Erro ao executar query no SAP HANA"
            )
        }
    }

    /**
     * Consulta da IA: validador mais rigido do HANA, corte em [maxRows] e [timeoutSeconds] no proprio statement,
     * e transacao sempre desfeita no fim, para nada que escape do validador ficar gravado. Devolve a mensagem
     * do HANA para a IA corrigir o SQL. Colunas vem do metadata, mesmo sem linhas.
     */
    fun execLimited(sql: String, maxRows: Int, timeoutSeconds: Int): QueryResult {
        HanaReadOnlySqlValidator.requireReadOnly(sql)
        val template = jdbcTemplate ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Conexao com SAP HANA nao configurada"
        )
        return try {
            template.execute(ConnectionCallback { con -> readInRolledBackTransaction(con, sql, maxRows, timeoutSeconds) })
                ?: QueryResult(emptyList(), emptyList())
        } catch (ex: DataAccessException) {
            log.warn("Erro na consulta da IA no SAP HANA: {}", ex.message)
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Erro no SAP HANA: ${ex.mostSpecificCause.message?.take(500)}"
            )
        }
    }

    private fun readInRolledBackTransaction(con: Connection, sql: String, maxRows: Int, timeoutSeconds: Int): QueryResult {
        val autoCommit = con.autoCommit
        con.autoCommit = false
        try {
            return con.prepareStatement(sql).use { ps ->
                ps.maxRows = maxRows
                ps.queryTimeout = timeoutSeconds
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        rows += columns.withIndex().associate { (i, name) -> name to readable(rs.getObject(i + 1)) }
                    }
                    QueryResult(columns, rows)
                }
            }
        } finally {
            con.rollback()
            con.autoCommit = autoCommit
        }
    }

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
        ReadOnlySqlValidator.requireReadOnly(sql)
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
}
