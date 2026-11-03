package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class AiApprovalServiceTest {

    private val repository = Mockito.mock(AiQueryApprovalRepository::class.java)
    private val properties = DbInspectorProperties()
    private val guard = AiSqlGuard(Mockito.mock(SqlExecClient::class.java), properties, jacksonObjectMapper())
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val service = AiApprovalService(
        repository, guard, Mockito.mock(AdminAuditService::class.java), properties, Clock.fixed(now, ZoneOffset.UTC)
    )
    private val stored = mutableListOf<AiQueryApprovalEntity>()
    private val tokenId = UUID.randomUUID()
    private val sql = "SELECT * FROM agricola.colheita"

    @BeforeEach
    fun setup() {
        Mockito.`when`(repository.save(Mockito.any(AiQueryApprovalEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<AiQueryApprovalEntity>(0).also {
                if (it.id == null) {
                    it.id = UUID.randomUUID()
                    it.createdAt = now
                    stored += it
                }
            }
        }
        Mockito.`when`(repository.findAllByTokenIdAndSqlHashOrderByCreatedAtDesc(anyUuid(), Mockito.anyString()))
            .thenAnswer { inv -> stored.filter { it.tokenId == inv.getArgument(0) && it.sqlHash == inv.getArgument(1) } }
        Mockito.`when`(repository.findById(anyUuid())).thenAnswer { inv ->
            Optional.ofNullable(stored.firstOrNull { it.id == inv.getArgument(0) })
        }
        authenticate(ai = true)
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `pedido repetido do mesmo SQL devolve o pendente existente`() {
        val first = service.request(AiApprovalRequest(sql, "preciso do ano inteiro", 3_000))
        val second = service.request(AiApprovalRequest("  $sql ; ", "de novo"))

        assertEquals(first.id, second.id)
        assertEquals(1, stored.size)
        assertEquals(3_000, first.linhasPedidas)
    }

    @Test
    fun `espaco dentro de texto muda o SQL e nao reaproveita aprovacao`() {
        val first = service.request(AiApprovalRequest("SELECT * FROM agricola.colheita c WHERE c.obs = 'A  B'", "x"))
        val second = service.request(AiApprovalRequest("SELECT * FROM agricola.colheita c WHERE c.obs = 'A B'", "x"))

        assertTrue(first.id != second.id)
    }

    @Test
    fun `nao aceita pedido para SQL que nunca seria liberado`() {
        assertThrows<AiValidationException> {
            service.request(AiApprovalRequest("SELECT pg_sleep(100)", "teste"))
        }
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `pessoa nao pede liberacao`() {
        authenticate(ai = false)

        val ex = assertThrows<AiRuleException> { service.request(AiApprovalRequest(sql, "x")) }

        assertEquals(HttpStatus.FORBIDDEN, ex.status)
    }

    @Test
    fun `aprovacao vale so ate expirar e respeita o teto de linhas`() {
        val id = UUID.fromString(service.request(AiApprovalRequest(sql, "x")).id)
        authenticate(ai = false)

        val approved = service.approve(id, AiApprovalDecisionRequest(linhas = 999_999, minutos = 30))

        assertEquals(properties.ai.approvalMaxRows, approved.linhasAprovadas)
        assertEquals(now.plus(Duration.ofMinutes(30)).toEpochMilli(), approved.expiraEm)
        assertNotNull(service.findActive(tokenId, sql))
        assertNull(service.findActive(UUID.randomUUID(), sql))

        stored.single().expiresAt = now.minusSeconds(1)
        assertNull(service.findActive(tokenId, sql))
    }

    @Test
    fun `negado nao vale e nao pode ser decidido de novo`() {
        val id = UUID.fromString(service.request(AiApprovalRequest(sql, "x")).id)
        authenticate(ai = false)

        service.deny(id, AiApprovalDecisionRequest(nota = "periodo grande demais"))

        assertNull(service.findActive(tokenId, sql))
        val ex = assertThrows<ResponseStatusException> { service.approve(id, AiApprovalDecisionRequest()) }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `token nao ve pedido de outro token`() {
        val id = UUID.fromString(service.request(AiApprovalRequest(sql, "x")).id)
        authenticate(ai = true, otherToken = UUID.randomUUID())

        val ex = assertThrows<ResponseStatusException> { service.getForToken(id) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    private fun authenticate(ai: Boolean, otherToken: UUID? = null) {
        val principal = AuthUserPrincipal(UUID.randomUUID(), "Ana", "ana@example.com", "", true, listOf("ROLE_ADMIN"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities).apply {
                if (ai) details = AiTokenAuthenticationDetails(otherToken ?: tokenId, "t")
            }
    }

    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID(0, 0)
    }
}
