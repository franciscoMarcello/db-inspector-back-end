package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.web.UpstreamContext
import com.chico.dbinspector.web.UpstreamContextFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/** Exercita o servidor MCP de verdade: JSON-RPC pelo servlet do transporte, como um cliente faria. */
class AiMcpServerTest {

    private val mapper = jacksonObjectMapper()
    private val reportService = Mockito.mock(AiReportService::class.java)
    private val catalogService = Mockito.mock(AiCatalogService::class.java)
    private val queryService = Mockito.mock(AiQueryService::class.java)
    private val approvalService = Mockito.mock(AiApprovalService::class.java)
    private val upstream = Mockito.mock(UpstreamContextFactory::class.java)
    private val properties = DbInspectorProperties()
    private val config = AiMcpConfig()
    private val jsonMapper = config.aiMcpJsonMapper(mapper)
    private val transport: HttpServletStatelessServerTransport = config.aiMcpTransport(jsonMapper, properties)
    private val tools = AiMcpTools(
        reportService, catalogService, queryService, approvalService,
        AiInstructionsService(properties, catalogService), upstream, jsonMapper, mapper,
        Validation.buildDefaultValidatorFactory().validator
    )
    private val server: McpStatelessSyncServer = config.aiMcpServer(transport, jsonMapper, tools)

    private val ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer x")
    private val tokenId = UUID.randomUUID()

    init {
        Mockito.`when`(upstream.create()).thenReturn(ctx)
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
        server.close()
    }

    @Test
    fun `initialize devolve as instrucoes de uso`() {
        val result = call("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")

        assertTrue(result["instructions"].asText().contains("ler_instrucoes"))
    }

    @Test
    fun `lista as ferramentas e nenhuma publica, exclui ou libera`() {
        val names = call("tools/list", "{}")["tools"].map { it["name"].asText() }.toSet()

        assertTrue(names.containsAll(setOf("ler_instrucoes", "buscar_tabelas", "consultar", "validar", "criar_rascunho", "salvar_rascunho", "previa")))
        assertFalse(names.any { it.contains("public") || it.contains("exclu") || it.contains("restaur") || it.contains("token") || it.contains("aprovar") })
    }

    @Test
    fun `ferramenta roda com a autenticacao de quem chamou`() {
        var seen: Authentication? = null
        val request = AiQueryRequest("SELECT 1 AS a")
        Mockito.`when`(queryService.query(request, ctx)).thenAnswer {
            seen = SecurityContextHolder.getContext().authentication
            AiQueryResponse(listOf("a"), listOf(mapOf("a" to 1)), 1, false, 200, 1.0, 3, null, null)
        }
        val auth = authenticate("REPORT_RUN", "REPORT_READ")

        val result = call("tools/call", """{"name":"consultar","arguments":{"sql":"SELECT 1 AS a"}}""")

        assertFalse(result["isError"]?.asBoolean() ?: false)
        assertTrue(result["content"][0]["text"].asText().contains("\"colunas\""))
        assertSame(auth, seen)
    }

    @Test
    fun `sem autenticacao a ferramenta recusa`() {
        SecurityContextHolder.clearContext()

        val result = call("tools/call", """{"name":"consultar","arguments":{"sql":"SELECT 1"}}""")

        assertTrue(result["isError"].asBoolean())
        assertTrue(result["content"][0]["text"].asText().contains("nao_autenticado"))
    }

    @Test
    fun `sem a permissao exigida a ferramenta recusa`() {
        authenticate("REPORT_READ")

        val result = call("tools/call", """{"name":"criar_rascunho","arguments":{"definicao":{"nome":"x","sql":"SELECT 1"}}}""")

        assertTrue(result["isError"].asBoolean())
        assertTrue(result["content"][0]["text"].asText().contains("REPORT_WRITE"))
    }

    @Test
    fun `problemas de validacao voltam legiveis para a IA corrigir`() {
        authenticate("REPORT_WRITE", "REPORT_RUN", "REPORT_READ")
        val body = AiReportDraftRequest(AiReportDefinition(nome = "x", sql = "SELECT nextval('s')"))
        Mockito.`when`(reportService.createDraft(body, ctx)).thenThrow(
            AiValidationException(listOf(AiProblem("sql", "funcao_proibida", "A funcao 'nextval' nao e permitida", 1)))
        )

        val result = call("tools/call", """{"name":"criar_rascunho","arguments":{"definicao":{"nome":"x","sql":"SELECT nextval('s')"}}}""")

        assertTrue(result["isError"].asBoolean())
        val error = mapper.readTree(result["content"][0]["text"].asText())
        assertEquals("validacao_falhou", error["erro"].asText())
        assertEquals("funcao_proibida", error["problemas"][0]["regra"].asText())
    }

    @Test
    fun `argumento invalido e recusado antes do service`() {
        authenticate("REPORT_WRITE", "REPORT_RUN", "REPORT_READ")

        val result = call("tools/call", """{"name":"criar_rascunho","arguments":{"definicao":{"nome":"","sql":"SELECT 1"}}}""")

        assertTrue(result["isError"].asBoolean())
        Mockito.verifyNoInteractions(reportService)
    }

    @Test
    fun `ler_instrucoes devolve o AGENTS com a URL e os limites preenchidos`() {
        authenticate("REPORT_READ")

        val text = call("tools/call", """{"name":"ler_instrucoes","arguments":{}}""")["content"][0]["text"].asText()

        assertTrue(text.contains("http://localhost/api/db/ai/mcp"))
        assertTrue(text.contains("`agricola`"))
        assertFalse(text.contains("{{"))
    }

    @Test
    fun `chamada de pagina de navegador com Origin e recusada`() {
        authenticate("REPORT_READ")

        val response = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""", origin = "https://evil.example")

        assertNotEquals(200, response.status)
    }

    private fun authenticate(vararg authorities: String): Authentication {
        val principal = AuthUserPrincipal(UUID.randomUUID(), "IA: t", "ana@example.com", "", true, authorities.toList())
        val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities).apply {
            details = AiTokenAuthenticationDetails(tokenId, "t")
        }
        SecurityContextHolder.getContext().authentication = auth
        return auth
    }

    private fun call(method: String, params: String): JsonNode {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        assertEquals(200, response.status, response.contentAsString)
        val body = mapper.readTree(response.contentAsString)
        return body["result"] ?: error("Sem result: ${response.contentAsString}")
    }

    private fun post(body: String, origin: String? = null): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", AiMcpConfig.PATH).apply {
            serverName = "localhost"
            serverPort = 80
            contentType = "application/json"
            addHeader("Accept", "application/json, text/event-stream")
            origin?.let { addHeader("Origin", it) }
            setContent(body.toByteArray())
        }
        val response = MockHttpServletResponse()
        transport.service(request, response)
        return response
    }
}
