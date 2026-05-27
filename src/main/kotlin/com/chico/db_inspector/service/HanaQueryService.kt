package com.chico.dbinspector.service

import com.chico.dbinspector.util.ReadOnlySqlValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
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
