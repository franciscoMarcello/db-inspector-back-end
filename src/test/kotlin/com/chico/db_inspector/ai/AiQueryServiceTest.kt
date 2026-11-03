package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AiQueryServiceTest {

    private val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
    private val approvals = Mockito.mock(AiApprovalService::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)
    private val properties = DbInspectorProperties().apply {
        ai.queryMaxRows = 2
        ai.queriesPerMinute = 3
    }
    private val guard = AiSqlGuard(sqlExecClient, properties, jacksonObjectMapper())
    private val service = AiQueryService(guard, AiQueryLimiter(properties), approvals, sqlExecClient, auditService, properties)
    private val ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer x")
    private val tokenId = UUID.randomUUID()
    private val executed = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        val principal = AuthUserPrincipal(UUID.randomUUID(), "IA: t", "ana@example.com", "", true, listOf("REPORT_RUN"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities).apply {
                details = AiTokenAuthenticationDetails(tokenId, "t")
            }
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            val query = inv.getArgument<String>(2)
            executed += query
            if (query.contains("pg_proc")) return@thenAnswer mapOf("data" to emptyList<Any>())
            if (query.startsWith("EXPLAIN")) {
                mapOf("data" to listOf(mapOf("QUERY PLAN" to plan(cost = 10.0))))
            } else {
                mapOf(
                    "data" to listOf(mapOf("status" to "E", "n" to 1), mapOf("status" to "A", "n" to 2), mapOf("status" to "C", "n" to 3)),
                    "fields" to listOf(mapOf("name" to "status"), mapOf("name" to "n"))
                )
            }
        }
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `consulta roda EXPLAIN antes e embrulha com LIMIT para saber se cortou`() {
        val response = service.query(AiQueryRequest("SELECT status, count(*) n FROM agricola.colheita GROUP BY status"), ctx)

        val sent = executed.filterNot { it.contains("pg_proc") }
        assertTrue(sent[0].startsWith("EXPLAIN (VERBOSE, FORMAT JSON) SELECT status"))
        assertTrue(sent[1].endsWith("ai_consulta LIMIT 3"))
        assertEquals(listOf("status", "n"), response.colunas)
        assertEquals(2, response.quantidade)
        assertTrue(response.truncado)
        assertEquals(10.0, response.custoEstimado)
    }

    @Test
    fun `pedido acima do limite e reduzido com aviso`() {
        val response = service.query(AiQueryRequest("SELECT 1 FROM agricola.colheita", limite = 500), ctx)

        assertEquals(2, response.limiteAplicado)
        assertTrue(response.aviso?.contains("solicite liberacao") == true)
    }

    @Test
    fun `liberacao vigente amplia linhas e ignora o custo`() {
        val sql = "SELECT * FROM agricola.colheita"
        Mockito.`when`(approvals.findActive(tokenId, sql)).thenReturn(
            AiQueryApprovalEntity(
                id = UUID.randomUUID(), status = AiApprovalStatus.APPROVED.name, approvedRows = 1_000,
                expiresAt = Instant.now().plusSeconds(600)
            )
        )
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), Mockito.startsWith("EXPLAIN") ?: "", anyDuration()))
            .thenReturn(mapOf("data" to listOf(mapOf("QUERY PLAN" to plan(cost = properties.ai.maxPlanCost * 10)))))

        val response = service.query(AiQueryRequest(sql, limite = 1_000), ctx)

        assertEquals(1_000, response.limiteAplicado)
        assertFalse(response.liberacaoId.isNullOrBlank())
    }

    @Test
    fun `SQL proibido nao chega ao banco`() {
        assertThrows<AiValidationException> { service.query(AiQueryRequest("SELECT nextval('s')"), ctx) }

        assertTrue(executed.isEmpty())
    }

    @Test
    fun `limite por minuto devolve 429 com dica`() {
        repeat(3) { service.query(AiQueryRequest("SELECT 1 FROM agricola.colheita"), ctx) }

        val ex = assertThrows<AiRuleException> { service.query(AiQueryRequest("SELECT 1 FROM agricola.colheita"), ctx) }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status)
        assertEquals("limite_de_frequencia", ex.regra)
    }

    private fun plan(cost: Double) = listOf(
        mapOf("Plan" to mapOf("Total Cost" to cost, "Schema" to "agricola", "Relation Name" to "colheita"))
    )

    private fun anyString(): String {
        Mockito.anyString()
        return ""
    }

    private fun anyDuration(): Duration {
        Mockito.any(Duration::class.java)
        return Duration.ZERO
    }
}
