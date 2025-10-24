package com.chico.dbinspector.controller

import com.chico.db_inspector.model.SqlQuery
import com.chico.dbinspector.service.SqlExecClient
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["http://localhost:4200"])
@RestController
@RequestMapping("/api/db") // antes era /test
class SqlMetadataController(private val sql: SqlExecClient) {

    // --- Utils ---------------------------------------------------------------

    private fun validateExternalUrl(url: String) {
        val u = java.net.URI(url)
        require(u.scheme == "http" || u.scheme == "https") { "URL deve ser http(s)" }
        val host = u.host ?: error("URL inválida")
        require(!host.equals("localhost", true) && host != "127.0.0.1") { "Host não permitido" }
    }

    private fun resolveBearer(authorization: String?, apiToken: String?): String =
        when {
            !authorization.isNullOrBlank() -> authorization // já vem "Bearer ..."
            !apiToken.isNullOrBlank()      -> "Bearer $apiToken"
            else                           -> error("Token ausente: informe Authorization: Bearer <...> ou X-API-Token")
        }

    // permite apenas [a-zA-Z0-9_], 1..63 chars (nome padrão de identificador)
    private fun validateIdent(name: String, field: String) {
        require(name.length in 1..63) { "$field inválido: tamanho" }
        require(Regex("^[A-Za-z0-9_]+\$").matches(name)) { "$field inválido: apenas [A-Za-z0-9_]" }
    }

    // --- Endpoints -----------------------------------------------------------

    @GetMapping("/ping")
    fun ping() = mapOf("status" to "ok")

    @GetMapping(
        path = ["/schemas"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun listSchemas(
        @RequestHeader("X-SQL-EXEC-URL") endpointUrl: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(value = "X-API-Token", required = false) apiToken: String?,
    ): Any {
        validateExternalUrl(endpointUrl)
        val bearer = resolveBearer(authorization, apiToken)

        val query = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
            ORDER BY schema_name;
        """.trimIndent()

        return sql.exec(endpointUrl, bearer, query)
    }

    // Listar todas as tabelas de um schema
    @GetMapping(
        path = ["/{schema}/tables"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun listTables(
        @PathVariable schema: String,
        @RequestHeader("X-SQL-EXEC-URL") endpointUrl: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(value = "X-API-Token", required = false) apiToken: String?,
    ): Any {
        validateIdent(schema, "schema")
        validateExternalUrl(endpointUrl)
        val bearer = resolveBearer(authorization, apiToken)

        val query = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = '${schema}'
              AND table_type = 'BASE TABLE'
            ORDER BY table_name;
        """.trimIndent()

        return sql.exec(endpointUrl, bearer, query)
    }

    // Detalhes de uma tabela
    @GetMapping(
        path = ["/{schema}/{table}/details"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun tableDetails(
        @PathVariable schema: String,
        @PathVariable table: String,
        @RequestHeader("X-SQL-EXEC-URL") endpointUrl: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(value = "X-API-Token", required = false) apiToken: String?,
    ): Any {
        validateIdent(schema, "schema")
        validateIdent(table, "table")
        validateExternalUrl(endpointUrl)
        val bearer = resolveBearer(authorization, apiToken)

        val query = """
            WITH cols AS (
                SELECT
                    c.column_name,
                    c.data_type,
                    c.is_nullable = 'YES' AS is_nullable,
                    c.column_default,
                    EXISTS (
                      SELECT 1
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu
                        ON tc.constraint_name = kcu.constraint_name
                      WHERE tc.constraint_type = 'PRIMARY KEY'
                        AND kcu.column_name = c.column_name
                        AND kcu.table_name = c.table_name
                        AND kcu.table_schema = c.table_schema
                    ) AS is_pk,
                    EXISTS (
                      SELECT 1
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu
                        ON tc.constraint_name = kcu.constraint_name
                      WHERE tc.constraint_type = 'FOREIGN KEY'
                        AND kcu.column_name = c.column_name
                        AND kcu.table_name = c.table_name
                        AND kcu.table_schema = c.table_schema
                    ) AS is_fk
                FROM information_schema.columns c
                WHERE c.table_schema = '${schema}'
                  AND c.table_name = '${table}'
            ),
            relations AS (
                SELECT DISTINCT
                    kcu.column_name AS source_column,
                    ccu.table_name AS target_table,
                    ccu.column_name AS target_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.table_schema = '${schema}'
                  AND kcu.table_name = '${table}'
                LIMIT 50
            )
            SELECT json_build_object(
                'columns', (SELECT json_agg(cols) FROM cols),
                'relations', (SELECT json_agg(relations) FROM relations)
            );
        """.trimIndent()

        return sql.exec(endpointUrl, bearer, query)
    }

    // Relações (FKs) envolvendo a tabela
    @GetMapping(
        path = ["/{schema}/{table}/relations"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun tableRelations(
        @PathVariable schema: String,
        @PathVariable table: String,
        @RequestHeader("X-SQL-EXEC-URL") endpointUrl: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(value = "X-API-Token", required = false) apiToken: String?,
    ): Any {
        validateIdent(schema, "schema")
        validateIdent(table, "table")
        validateExternalUrl(endpointUrl)
        val bearer = resolveBearer(authorization, apiToken)

        val query = """
            SELECT
                CASE
                    WHEN src.relname = '${table}' THEN 'OUTGOING'
                    WHEN tgt.relname = '${table}' THEN 'INCOMING'
                END AS relation_type,
                src_ns.nspname AS source_schema,
                src.relname AS source_table,
                src_col.attname AS source_column,
                tgt_ns.nspname AS target_schema,
                tgt.relname AS target_table,
                tgt_col.attname AS target_column
            FROM pg_constraint c
            JOIN pg_class src ON src.oid = c.conrelid
            JOIN pg_namespace src_ns ON src_ns.oid = src.relnamespace
            JOIN pg_class tgt ON tgt.oid = c.confrelid
            JOIN pg_namespace tgt_ns ON tgt_ns.oid = tgt.relnamespace
            JOIN unnest(c.conkey) WITH ORDINALITY AS s(attnum, ord) ON TRUE
            JOIN unnest(c.confkey) WITH ORDINALITY AS t(attnum, ord) ON s.ord = t.ord
            JOIN pg_attribute src_col ON src_col.attrelid = src.oid AND src_col.attnum = s.attnum
            JOIN pg_attribute tgt_col ON tgt_col.attrelid = tgt.oid AND tgt_col.attnum = t.attnum
            WHERE c.contype = 'f'
              AND (
                    (src_ns.nspname = '${schema}' AND src.relname = '${table}')
                 OR (tgt_ns.nspname = '${schema}' AND tgt.relname = '${table}')
              )
            ORDER BY relation_type, target_table
            LIMIT 50;
        """.trimIndent()

        return sql.exec(endpointUrl, bearer, query)
    }

    // Execução arbitrária de query (POST /api/db/query)
    @PostMapping(
        path = ["/query"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun runQuery(
        @RequestHeader("X-SQL-EXEC-URL") endpointUrl: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(value = "X-API-Token", required = false) apiToken: String?,
        @RequestBody body: SqlQuery
    ): ResponseEntity<Any> {
        validateExternalUrl(endpointUrl)
        val bearer = resolveBearer(authorization, apiToken)
        val q = body.query.trim()

        val result = sql.exec(
            endpointUrl,
            bearer,
            q,
            body.asDict ?: true,
            body.withDescription ?: true
        )
        return ResponseEntity.ok(result)
    }
}
