package com.chico.dbinspector.controller

import com.chico.db_inspector.model.SqlQuery
import com.chico.dbinspector.service.SqlExecClient
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.OutputStreamWriter
@CrossOrigin(origins = ["http://localhost:4200"])
@RestController
@RequestMapping("/test")
class TestController(private val sql: SqlExecClient) {

    @GetMapping("/ping")
    fun ping() = mapOf("status" to "ok")

    @GetMapping("/schemas")
    fun listSchemas(): Any {
        val query = """
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        ORDER BY schema_name;
    """.trimIndent()

        return sql.exec(query)
    }

    // 1️⃣ Listar todas as tabelas do schema
    @GetMapping("/{schema}/tables")
    fun listTables(@PathVariable schema: String): Any {
        val query = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = '$schema'
              AND table_type = 'BASE TABLE'
            ORDER BY table_name;
        """.trimIndent()
        return sql.exec(query)
    }

    @GetMapping("/{schema}/{table}/details")
    fun tableDetails(@PathVariable schema: String, @PathVariable table: String): Any {
        val query = """
        WITH cols AS (
            SELECT
                c.column_name,
                c.data_type,
                c.is_nullable = 'YES' AS is_nullable,
                c.column_default,
                (SELECT COUNT(*) > 0 FROM information_schema.table_constraints tc
                 JOIN information_schema.key_column_usage kcu
                   ON tc.constraint_name = kcu.constraint_name
                 WHERE tc.constraint_type = 'PRIMARY KEY'
                   AND kcu.column_name = c.column_name
                   AND kcu.table_name = c.table_name) AS is_pk,
                (SELECT COUNT(*) > 0 FROM information_schema.table_constraints tc
                 JOIN information_schema.key_column_usage kcu
                   ON tc.constraint_name = kcu.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = c.column_name
                   AND kcu.table_name = c.table_name) AS is_fk
            FROM information_schema.columns c
            WHERE c.table_schema = '$schema'
              AND c.table_name = '$table'
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
              AND kcu.table_schema = '$schema'
              AND kcu.table_name = '$table'
            LIMIT 1
        )
        SELECT json_build_object(
            'columns', (SELECT json_agg(cols) FROM cols),
            'relations', (SELECT json_agg(relations) FROM relations)
        );
    """.trimIndent()

        return sql.exec(query)
    }


    @GetMapping("/{schema}/{table}/relations")
    fun tableRelations(@PathVariable schema: String, @PathVariable table: String): Any {
        val query = """
        SELECT
            CASE 
                WHEN src.relname = '$table' THEN 'OUTGOING'
                WHEN tgt.relname = '$table' THEN 'INCOMING'
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
          AND (src_ns.nspname = '$schema' AND src.relname = '$table'
               OR tgt_ns.nspname = '$schema' AND tgt.relname = '$table')
        ORDER BY relation_type, target_table
        LIMIT 50;
    """.trimIndent()

        return sql.exec(query)
    }


    @PostMapping("/query-csv-auto", produces = ["text/csv"])
    fun executeQueryAsCsvAuto(
        @RequestBody body: Map<String, String>,
        response: HttpServletResponse
    ) {
        val baseQuery = body["query"] ?: throw IllegalArgumentException("Campo 'query' é obrigatório.")
        val fileName = body["fileName"] ?: "resultado.csv"
        val limit = (body["limit"] ?: "1000").toInt() // tamanho do lote

        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader("Content-Disposition", "attachment; filename=$fileName")

        val writer = OutputStreamWriter(response.outputStream, Charsets.UTF_8)
        writer.write("\uFEFF") // BOM p/ Excel PT-BR
        val delimiter = ';'

        var offset = 0
        var wroteHeader = false

        while (true) {
            val paginatedQuery = "$baseQuery LIMIT $limit OFFSET $offset"
            println("➡️ Buscando lote OFFSET=$offset LIMIT=$limit")

            val result = sql.exec(paginatedQuery)
            val fields = (result["fields"] as? List<Map<String, Any?>>) ?: emptyList()
            val rawData = result["data"] as? List<Map<String, Any?>> ?: emptyList()
            if (rawData.isEmpty()) {
                println("✅ Fim da exportação, nenhum registro no lote OFFSET=$offset")
                break
            }

            // Detecta formato (normal ou json_build_object)
            val data = if (rawData.first().containsKey("json_build_object")) {
                rawData.mapNotNull { it["json_build_object"] as? Map<String, Any?> }
            } else {
                rawData
            }

            // Cabeçalho (só na primeira página)
            if (!wroteHeader) {
                val header = fields.joinToString(delimiter.toString()) { it["name"].toString() }
                writer.write(header + "\n")
                wroteHeader = true
            }

            // Escreve linhas
            for (row in data) {
                val line = fields.joinToString(delimiter.toString()) { field ->
                    val value = row[field["name"].toString()]
                    if (value == null) "" else "\"${value.toString().replace("\"", "\"\"")}\""
                }
                writer.write(line + "\n")
            }

            writer.flush()

            // Se o lote retornou menos que o limite, terminou
            if (data.size < limit) {
                println("🏁 Último lote (OFFSET=$offset, ${data.size} registros)")
                break
            }

            offset += limit
        }

        writer.flush()
        writer.close()
    }
    @PostMapping("/query")
    fun runQuery(@RequestBody body: SqlQuery): ResponseEntity<Any> {
        val q = body.query.trim()

        val result = sql.exec(
            q,                           // 1º: query
            body.asDict ?: true,         // 2º: asDict
            body.withDescription ?: true // 3º: withDescription
        )
        return ResponseEntity.ok(result)
    }


}
