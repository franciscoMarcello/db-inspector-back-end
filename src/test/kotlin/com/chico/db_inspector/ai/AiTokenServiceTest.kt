package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AppUserDetailsService
import com.chico.dbinspector.auth.AppUserEntity
import com.chico.dbinspector.auth.AppUserRepository
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.report.ReportVersionService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class AiTokenServiceTest {

    private val repository = Mockito.mock(AiTokenRepository::class.java)
    private val userRepository = Mockito.mock(AppUserRepository::class.java)
    private val userDetailsService = Mockito.mock(AppUserDetailsService::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val properties = DbInspectorProperties()

    private val service = AiTokenService(repository, userRepository, userDetailsService, auditService, properties, clock)

    private val creatorId = UUID.randomUUID()
    private val creator = AppUserEntity(id = creatorId, name = "Ana", email = "ana@example.com", active = true)
    private val stored = mutableListOf<AiTokenEntity>()

    @BeforeEach
    fun setup() {
        Mockito.`when`(repository.save(Mockito.any(AiTokenEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<AiTokenEntity>(0).also {
                if (it.id == null) {
                    it.id = UUID.randomUUID()
                    it.createdAt = now
                    stored += it
                }
            }
        }
        Mockito.`when`(repository.findByTokenHash(Mockito.anyString())).thenAnswer { inv ->
            stored.firstOrNull { it.tokenHash == inv.getArgument<String>(0) }
        }
        Mockito.`when`(userRepository.findById(creatorId)).thenReturn(Optional.of(creator))
        creatorAuthorities("ROLE_ANALISTA", "REPORT_READ", "REPORT_RUN", "SQL_QUERY_EXECUTE", "EMAIL_SEND")
        authenticateAs(creatorId, "ana@example.com")
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `deve guardar so o hash e devolver o valor uma unica vez`() {
        val created = service.create(AiTokenCreateRequest(name = "claude-code", validityDays = 10))

        assertTrue(created.token.startsWith("rpt_"))
        assertEquals(1, stored.size)
        assertFalse(stored[0].tokenHash.contains(created.token))
        assertEquals(64, stored[0].tokenHash.length)
        assertEquals(created.token.take(12), stored[0].prefix)
        assertEquals(now.plus(Duration.ofDays(10)), stored[0].expiresAt)
        assertFalse(service.list().any { it.prefix == created.token })
    }

    @Test
    fun `deve recusar validade acima do teto`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(AiTokenCreateRequest(name = "x", validityDays = properties.ai.tokenMaxDays + 1))
        }
    }

    @Test
    fun `token valido age como o criador so com permissoes de relatorio`() {
        val value = service.create(AiTokenCreateRequest(name = "gpt", validityDays = 5)).token

        val principal = assertNotNull(service.authenticate(value)).principal
        val authorities = principal.authorities.map { it.authority }.toSet()

        assertEquals(creatorId, principal.userId)
        assertEquals("ana@example.com", principal.username)
        assertTrue("REPORT_READ" in authorities)
        assertTrue("REPORT_RUN" in authorities)
        assertTrue("ROLE_ANALISTA" in authorities)
        assertTrue(ReportVersionService.AI_AGENT_AUTHORITY in authorities)
        assertFalse("SQL_QUERY_EXECUTE" in authorities)
        assertFalse("EMAIL_SEND" in authorities)
        assertFalse("REPORT_WRITE" in authorities)
    }

    @Test
    fun `token de admin recebe todas as permissoes permitidas a IA e nenhuma outra`() {
        creatorAuthorities("ROLE_ADMIN", "REPORT_READ", "EMAIL_SEND", "SQL_QUERY_EXECUTE")
        val value = service.create(AiTokenCreateRequest(name = "admin", validityDays = 5)).token

        val authorities = assertNotNull(service.authenticate(value)).principal.authorities.map { it.authority }.toSet()

        assertTrue(authorities.containsAll(AiTokenService.ALLOWED_PERMISSIONS))
        assertFalse("EMAIL_SEND" in authorities)
        assertFalse("SQL_QUERY_EXECUTE" in authorities)
    }

    @Test
    fun `token expirado nao autentica`() {
        val value = service.create(AiTokenCreateRequest(name = "x", validityDays = 1)).token
        stored[0].expiresAt = now.minusSeconds(1)

        assertNull(service.authenticate(value))
    }

    @Test
    fun `token revogado nao autentica e nao pode ser revogado duas vezes`() {
        val created = service.create(AiTokenCreateRequest(name = "x", validityDays = 1))
        val id = UUID.fromString(created.id)
        Mockito.`when`(repository.findById(id)).thenReturn(Optional.of(stored[0]))

        service.revoke(id)

        assertNull(service.authenticate(created.token))
        val ex = assertThrows(ResponseStatusException::class.java) { service.revoke(id) }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `token de criador desativado nao autentica`() {
        val value = service.create(AiTokenCreateRequest(name = "x", validityDays = 1)).token
        creator.active = false

        assertNull(service.authenticate(value))
    }

    @Test
    fun `valor desconhecido ou sem prefixo nao autentica`() {
        assertNull(service.authenticate("rpt_inexistente"))
        assertNull(service.authenticate("eyJhbGciOiJIUzI1NiJ9.x.y"))
    }

    private fun creatorAuthorities(vararg authorities: String) {
        Mockito.`when`(userDetailsService.loadUserByUsername("ana@example.com")).thenReturn(
            AuthUserPrincipal(
                userId = creatorId,
                name = "Ana",
                email = "ana@example.com",
                passwordHash = "x",
                active = true,
                authoritiesList = authorities.toList()
            )
        )
    }

    private fun authenticateAs(userId: UUID, email: String) {
        val principal = AuthUserPrincipal(userId, "Ana", email, "", true, listOf("ROLE_ADMIN"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    }
}
