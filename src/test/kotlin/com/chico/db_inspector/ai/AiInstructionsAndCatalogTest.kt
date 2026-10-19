package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.report.JasperFormat
import com.chico.dbinspector.report.ReportVariableRequest
import com.chico.dbinspector.report.ReportVariableService
import com.chico.dbinspector.service.SqlExecClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipInputStream

class AiInstructionsAndCatalogTest {

    private val mapper = jacksonObjectMapper()
    private val properties = DbInspectorProperties()
    private val guard = AiSqlGuard(Mockito.mock(SqlExecClient::class.java), properties, mapper)
    private val catalog = AiCatalogService(guard, Mockito.mock(SqlExecClient::class.java), mapper, properties)
    private val instructions = AiInstructionsService(properties, catalog)

    @Test
    fun `catalogo so mostra o que esta liberado`() {
        val schemas = catalog.schemas().map { it.schema }

        assertTrue("agricola" in schemas)
        assertTrue("sap" in schemas)
        // public e br so aparecem pelas tabelas de cadastro liberadas uma a uma.
        assertEquals(listOf("res_partner"), catalog.search("res_partner", "public").map { it.tabela.substringAfter('.') })
        assertTrue(catalog.search("auth_user", null).isEmpty())
        assertTrue(catalog.search("funcionario", null).isEmpty())
        assertEquals(listOf("br.centro_custo"), catalog.search("centro_custo", "br").map { it.tabela })
    }

    @Test
    fun `coluna sensivel nem aparece na descricao nem na busca por coluna`() {
        val description = catalog.describe("public", "res_partner", null)

        assertEquals(listOf("id", "nome"), description.colunas.map { it.nome })
        assertTrue(catalog.search("site_password", null).isEmpty())
        assertTrue(catalog.search("senha_web", null).isEmpty())
    }

    @Test
    fun `br funcionario e public auth_user seguem fora mesmo com cadastros liberados`() {
        assertFalse(guard.isAllowed("br", "funcionario"))
        assertFalse(guard.isAllowed("br", "endereco_pessoa"))
        assertFalse(guard.isAllowed("public", "auth_user"))
        assertTrue(guard.isAllowed("br", "centro_custo"))
        assertTrue(guard.isAllowed("public", "res_partner"))
        assertThrows<AiRuleException> { catalog.describe("br", "funcionario", null) }
    }

    @Test
    fun `liberar tabela avulsa do public a torna visivel`() {
        properties.ai.allowedTables = listOf("public.res_partner")

        assertTrue(catalog.search("res_partner", null).any { it.tabela == "public.res_partner" })
        assertTrue(guard.isAllowed("public", "res_partner"))
        assertFalse(guard.isAllowed("public", "auth_user"))
    }

    @Test
    fun `busca por coluna encontra a tabela`() {
        val found = catalog.search("periodo_producao_id", "agricola").map { it.tabela }

        assertTrue("agricola.colheita" in found)
    }

    @Test
    fun `descricao traz FKs nos dois sentidos sem ir ao banco quando nao ha contexto`() {
        val description = catalog.describe("agricola", "colheita", null)

        assertTrue(description.colunas.any { it.nome == "status" })
        assertTrue(description.referencia.any { it.referencia == "agro.fazenda.id" && it.liberada })
        assertTrue(description.fonte.startsWith("catalogo"))
    }

    @Test
    fun `descricao recusa tabela fora da lista e nome invalido`() {
        assertThrows<AiRuleException> { catalog.describe("public", "auth_user", null) }
        assertThrows<Exception> { catalog.describe("agricola", "colheita'; --", null) }
    }

    @Test
    fun `instrucoes saem com URL e limites preenchidos e sem marcador sobrando`() {
        val text = instructions.read(null, "https://dbi.example.com")

        assertTrue(text.startsWith("# Relatorios do DB Inspector"))
        assertTrue(text.contains("https://dbi.example.com/api/db/ai/mcp"))
        assertTrue(text.contains("modelo-sintetico"))
        assertFalse(text.contains("{{"))
        assertFalse(instructions.openApi("https://dbi.example.com").contains("{{"))
    }

    @Test
    fun `pacote zip traz AGENTS, guia de conexao, openapi, exemplos e catalogo`() {
        val names = ZipInputStream(ByteArrayInputStream(instructions.packageZip("https://dbi.example.com"))).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }

        assertTrue("dbinspector-ia/AGENTS.md" in names)
        assertTrue("dbinspector-ia/COMO-CONECTAR.md" in names)
        assertTrue("dbinspector-ia/openapi.yaml" in names)
        assertTrue("dbinspector-ia/contexto/catalogo.json" in names)
        assertTrue("dbinspector-ia/exemplos/modelo-sintetico/template.jrxml" in names)
    }

    @Test
    fun `exemplos passam na guarda estatica e declaram exatamente as variaveis do SQL`() {
        val placeholder = Regex("(?<!:):([A-Za-z_][A-Za-z0-9_]*)")
        val examples = instructions.paths().filter { it.endsWith("definicao.json") }
        assertTrue(examples.isNotEmpty())
        examples.forEach { path ->
            val definition: AiReportDefinition = mapper.readValue(instructions.read(path, "x"))
            assertEquals(emptyList<AiProblem>(), guard.staticProblems(definition.sql), path)
            definition.sqlSap?.let { assertEquals(emptyList<AiProblem>(), guard.staticProblems(it, "sqlSap"), path) }
            assertEquals(placeholder.findAll(definition.sql).map { it.groupValues[1] }.toSet(), definition.variaveis.map { it.chave }.toSet(), path)
            ReportVariableService().normalizeVariables(definition.variaveis.map {
                ReportVariableRequest(it.chave, it.rotulo, it.tipo, it.obrigatoria, it.multipla, it.padrao, it.sqlOpcoes)
            })
            definition.variaveis.mapNotNull { it.sqlOpcoes }.forEach { assertEquals(emptyList<AiProblem>(), guard.staticProblems(it), path) }
        }
    }

    @Test
    fun `contexto externo substitui o catalogo e acrescenta o glossario, sem sair da pasta`() {
        val dir = java.nio.file.Files.createTempDirectory("ai-context")
        try {
            java.nio.file.Files.writeString(
                dir.resolve("catalogo.json"),
                """{"geradoEm":"2030-01-01","tabelas":[{"schema":"frota","nome":"veiculo","pk":["id"],"colunas":[["id","bigint",true]],"fks":[]}]}"""
            )
            java.nio.file.Files.createDirectories(dir.resolve("glossario"))
            java.nio.file.Files.writeString(dir.resolve("glossario/README.md"), "# glossario externo")
            java.nio.file.Files.writeString(dir.resolve("segredo.md"), "fora do glossario")
            val external = DbInspectorProperties().apply { ai.contextPath = dir.toString() }
            val externalCatalog = AiCatalogService(guard, Mockito.mock(SqlExecClient::class.java), mapper, external)
            val externalInstructions = AiInstructionsService(external, externalCatalog)

            assertEquals(listOf("frota"), externalCatalog.schemas().map { it.schema })
            assertEquals("2030-01-01", externalCatalog.generatedAt())
            assertEquals("# glossario externo", externalInstructions.read("glossario/README.md", "x"))
            assertFalse("segredo.md" in externalInstructions.paths())
            assertTrue(externalCatalog.catalogFile()!!.isNotEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exemplos e glossario vem da pasta de contexto, e sem exemplos o texto avisa`() {
        val dir = java.nio.file.Files.createTempDirectory("ai-context")
        try {
            val external = DbInspectorProperties().apply { ai.contextPath = dir.toString() }
            // O modelo sintetico do classpath de teste continua la; o exemplo externo se soma a ele.
            java.nio.file.Files.createDirectories(dir.resolve("exemplos/producao-x"))
            java.nio.file.Files.writeString(dir.resolve("exemplos/producao-x/definicao.json"), "{}")
            java.nio.file.Files.writeString(dir.resolve("exemplos/producao-x/template.jrxml"), "<jasperReport/>")
            java.nio.file.Files.writeString(dir.resolve("exemplos/producao-x/leia-me.txt"), "nao e lido")
            val comExemplos = AiInstructionsService(external, AiCatalogService(guard, Mockito.mock(SqlExecClient::class.java), mapper, external))

            assertTrue("exemplos/producao-x/definicao.json" in comExemplos.paths())
            assertTrue("exemplos/producao-x/template.jrxml" in comExemplos.paths())
            assertFalse("exemplos/producao-x/leia-me.txt" in comExemplos.paths())
            assertTrue(comExemplos.read(null, "x").contains("producao-x"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sem pasta de contexto o glossario nao existe e o catalogo vem do classpath`() {
        assertFalse(instructions.paths().any { it.startsWith("glossario/") })
        assertTrue(catalog.schemas().isNotEmpty())
    }

    @Test
    fun `FMT formata em pt-BR e trata nulo`() {
        assertEquals("R$ 1.234,57", JasperFormat.moeda(BigDecimal("1234.567")))
        assertEquals("1.234,6", JasperFormat.numero(1234.56, 1))
        assertEquals("12,50%", JasperFormat.percentual(12.5))
        assertEquals("1.235", JasperFormat.inteiro("1234.5"))
        assertEquals("01/03/2025", JasperFormat.data(LocalDate.of(2025, 3, 1)))
        assertEquals("01/03/2025 10:30", JasperFormat.dataHora("2025-03-01 10:30:00"))
        assertEquals("", JasperFormat.moeda(null))
        assertEquals("", JasperFormat.data("nao e data"))
        assertEquals(12, JasperFormat.numero(1, 1_000_000_000).length)
        assertEquals("", JasperFormat.numero("1E999999999"))
    }
}
