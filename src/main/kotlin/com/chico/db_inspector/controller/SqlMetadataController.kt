package com.chico.dbinspector.controller

import com.chico.db_inspector.model.SqlQuery
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/db")
class SqlMetadataController(private val sql: SqlExecClient) {
    companion object {
        private const val DEFAULT_PAGE_SIZE = 200
        private const val MAX_PAGE_SIZE = 1_000
    }

    @GetMapping("/schemas", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listSchemas(ctx: UpstreamContext): Any {
        val query = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('pg_catalog','information_schema','pg_toast')
            ORDER BY schema_name;
        """.trimIndent()
        return sql.exec(ctx.endpointUrl, ctx.bearer, query)
    }

    @GetMapping("/{schema}/tables", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listTables(@PathVariable schema: String, ctx: UpstreamContext): Any {
        val query = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = '${schema}'
              AND table_type = 'BASE TABLE'
            ORDER BY table_name;
        """.trimIndent()
        return sql.exec(ctx.endpointUrl, ctx.bearer, query)
    }

    @GetMapping("/{schema}/{table}/details", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun tableDetails(@PathVariable schema: String, @PathVariable table: String, ctx: UpstreamContext): Any {
        val query = """
            WITH cols AS (
                SELECT
                    c.column_name,
                    c.data_type,
                    c.is_nullable = 'YES' AS is_nullable,
                    c.column_default,
                    EXISTS (
                      SELECT 1 FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                      WHERE tc.constraint_type = 'PRIMARY KEY'
                        AND kcu.column_name = c.column_name
                        AND kcu.table_name = c.table_name
                        AND kcu.table_schema = c.table_schema
                    ) AS is_pk,
                    EXISTS (
                      SELECT 1 FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                      WHERE tc.constraint_type = 'FOREIGN KEY'
                        AND kcu.column_name = c.column_name
                        AND kcu.table_name = c.table_name
                        AND kcu.table_schema = c.table_schema
                    ) AS is_fk
                FROM information_schema.columns c
                WHERE c.table_schema = '${schema}' AND c.table_name = '${table}'
            ),
            relations AS (
                SELECT DISTINCT
                    kcu.column_name AS source_column,
                    ccu.table_name AS target_table,
                    ccu.column_name AS target_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.table_schema = '${schema}' AND kcu.table_name = '${table}'
                LIMIT 50
            )
            SELECT json_build_object(
                'columns', (SELECT json_agg(cols) FROM cols),
                'relations', (SELECT json_agg(relations) FROM relations)
            );
        """.trimIndent()
        return sql.exec(ctx.endpointUrl, ctx.bearer, query)
    }

    @GetMapping("/{schema}/{table}/relations", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun tableRelations(@PathVariable schema: String, @PathVariable table: String, ctx: UpstreamContext): Any {
        val query = """
            SELECT
                CASE WHEN src.relname = '${table}' THEN 'OUTGOING'
                     WHEN tgt.relname = '${table}' THEN 'INCOMING' END AS relation_type,
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
              AND ((src_ns.nspname = '${schema}' AND src.relname = '${table}')
                OR  (tgt_ns.nspname = '${schema}' AND tgt.relname = '${table}'))
            ORDER BY relation_type, target_table
            LIMIT 50;
        """.trimIndent()
        return sql.exec(ctx.endpointUrl, ctx.bearer, query)
    }

    @PostMapping("/query", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun runQuery(@RequestBody body: SqlQuery, ctx: UpstreamContext): ResponseEntity<Any> {
        val q = body.query.trim()
        require(q.isNotEmpty()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(q)

        val page = (body.page ?: 0).coerceAtLeast(0)
        val size = (body.size ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val paginatedQuery = toPaginatedSelect(q, size, page * size)

        val result = sql.exec(
            ctx.endpointUrl,
            ctx.bearer,
            paginatedQuery,
            body.asDict ?: true,
            body.withDescription ?: true
        )
        val payload = linkedMapOf<String, Any?>(
            "page" to page,
            "size" to size,
            "query" to paginatedQuery
        )
        payload.putAll(result)
        return ResponseEntity.ok(payload)
    }

    @PostMapping("/query/all", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun runQueryAll(@RequestBody body: SqlQuery, ctx: UpstreamContext): ResponseEntity<Any> {
        val q = body.query.trim()
        require(q.isNotEmpty()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(q)
        val result = sql.exec(ctx.endpointUrl, ctx.bearer, q, body.asDict ?: true, body.withDescription ?: true)
        return ResponseEntity.ok(result)
    }

    private fun toPaginatedSelect(rawQuery: String, limit: Int, offset: Int): String {
        val query = rawQuery.trim().trimEnd(';').trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        val startsLikeReadOnly = query.startsWith("select", ignoreCase = true) ||
            query.startsWith("with", ignoreCase = true)
        require(startsLikeReadOnly) { "Paginacao disponivel apenas para SELECT/WITH. Use /query/all para outros comandos" }
        return "SELECT * FROM ($query) dbi_paginated LIMIT $limit OFFSET $offset"
    }
}
