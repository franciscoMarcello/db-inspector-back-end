package com.chico.dbinspector.service

import com.chico.dbinspector.util.ReadOnlySqlValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class HanaQueryService(
    @Qualifier("hanaJdbcTemplate")
    @Autowired(required = false)
    private val jdbcTemplate: JdbcTemplate?,
    @Qualifier("hanaNamedParameterJdbcTemplate")
    @Autowired(required = false)
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate?
) {
    private val log = LoggerFactory.getLogger(HanaQueryService::class.java)

    data class QueryResult(
        val columns: List<String>,
        val rows: List<Map<String, Any?>>
    )

    fun exec(sql: String): QueryResult = exec(sql, emptyMap())

    fun exec(sql: String, params: Map<String, Any?>): QueryResult {
        if (jdbcTemplate == null && namedParameterJdbcTemplate == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Conexao com SAP HANA nao configurada"
            )
        }
        ReadOnlySqlValidator.requireReadOnly(sql)
        return try {
            val rawRows: List<Map<String, Any?>> = if (params.isEmpty()) {
                val template = jdbcTemplate ?: throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Conexao com SAP HANA nao configurada"
                )
                template.queryForList(sql)
            } else {
                val namedTemplate = namedParameterJdbcTemplate ?: throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Conexao com SAP HANA sem suporte a parametros nomeados"
                )
                namedTemplate.queryForList(sql, MapSqlParameterSource(params))
            }.map { row -> row.mapValues { (_, v) -> v } }
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
}
