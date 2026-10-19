package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Instrucoes para agentes de IA, servidas pela API, pelo MCP e no pacote zip. Sao os arquivos de
 * resources/ai/instrucoes: mudou no repositorio e fez deploy, todo agente conectado ja le a versao
 * nova. Os limites e a URL sao preenchidos com os valores em vigor.
 */
@Service
class AiInstructionsService(
    private val properties: DbInspectorProperties,
    private val catalogService: AiCatalogService
) {
    companion object {
        const val MAIN = "AGENTS.md"
        private const val ROOT = "ai/instrucoes/"
        private val EXTERNAL_DIRS = mapOf(
            "glossario" to listOf(".md"),
            "exemplos" to listOf(".md", ".json", ".jrxml")
        )
    }

    private val files: Map<String, String> = load()
    private val openApiTemplate: String = javaClass.classLoader.getResourceAsStream("ai/openapi.yaml")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

    fun paths(): List<String> = files.keys.toList()

    fun read(path: String?, baseUrl: String): String {
        val key = path?.trim()?.removePrefix("/")?.takeIf { it.isNotBlank() } ?: MAIN
        val content = files[key] ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND, "Arquivo '$key' nao existe. Disponiveis: ${files.keys.joinToString(", ")}"
        )
        return render(content, baseUrl)
    }

    fun openApi(baseUrl: String): String = render(openApiTemplate, baseUrl)

    /** Pacote neutro: AGENTS.md, COMO-CONECTAR.md, openapi.yaml, glossario, exemplos e catalogo. */
    fun packageZip(baseUrl: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry("dbinspector-ia/$name"))
                zip.write(content)
                zip.closeEntry()
            }
            files.forEach { (path, content) -> add(path, render(content, baseUrl).toByteArray(Charsets.UTF_8)) }
            add("openapi.yaml", openApi(baseUrl).toByteArray(Charsets.UTF_8))
            catalogService.catalogFile()?.let { add("contexto/catalogo.json", it) }
        }
        return output.toByteArray()
    }

    /**
     * Glossario de regras de negocio e exemplos de relatorios reais, lidos de
     * dbinspector.ai.context-path (fora do git: descrevem o banco de producao). So as pastas
     * glossario/ (.md) e exemplos/ (.md, .json, .jrxml); nada fora delas e lido.
     */
    private fun externalContext(): Map<String, String> {
        val base = properties.ai.contextPath.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: return emptyMap()
        return EXTERNAL_DIRS.flatMap { (dir, extensions) ->
            val root = base.resolve(dir)
            if (!Files.isDirectory(root)) return@flatMap emptyList()
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && extensions.any { ext -> it.fileName.toString().endsWith(ext) } }
                    .toList()
                    .map { file -> "$dir/" + root.relativize(file).toString().replace('\\', '/') to Files.readString(file) }
            }
        }.toMap()
    }

    private fun render(content: String, baseUrl: String): String {
        val ai = properties.ai
        val examples = files.keys.filter { it.startsWith("exemplos/") }
            .map { it.removePrefix("exemplos/").substringBefore('/') }.distinct()
        return content
            .replace("{{BASE_URL}}", baseUrl.trimEnd('/'))
            .replace("{{SCHEMAS}}", ai.allowedSchemas.sorted().joinToString(", ") { "`$it`" })
            .replace(
                "{{TABELAS_EXTRAS}}",
                if (ai.allowedTables.isEmpty()) "Nenhuma tabela avulsa de outros schemas (ex.: `public`) esta liberada."
                else "Tambem liberadas: ${ai.allowedTables.sorted().joinToString(", ") { "`$it`" }}."
            )
            .replace("{{QUERY_MAX_ROWS}}", ai.queryMaxRows.toString())
            .replace("{{PER_MINUTE}}", ai.queriesPerMinute.toString())
            .replace("{{CONCURRENT}}", ai.maxConcurrentQueries.toString())
            .replace("{{PREVIEW_ROWS}}", ai.previewMaxRows.toString())
            .replace("{{PDF_ROWS}}", ai.pdfPreviewMaxRows.toString())
            .replace("{{MAX_COST}}", "%.0f".format(ai.maxPlanCost))
            .replace(
                "{{EXEMPLOS}}",
                examples.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" }
                    ?: "nenhum instalado (a pasta de contexto nao tem exemplos; peca a uma pessoa para instala-los)"
            )
            .replace("{{CATALOGO_DATA}}", catalogService.generatedAt().orEmpty())
    }

    private fun load(): Map<String, String> {
        val loaded = PathMatchingResourcePatternResolver().getResources("classpath*:$ROOT**")
            .filter { it.isReadable && it.filename?.contains('.') == true }
            .associate { resource ->
                val url = resource.url.toString()
                url.substring(url.lastIndexOf(ROOT) + ROOT.length) to
                    resource.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            .toMutableMap()
        loaded += externalContext()
        return loaded.toSortedMap(compareBy<String> { it != MAIN }.thenBy { it.count { c -> c == '/' } }.thenBy { it })
    }
}
