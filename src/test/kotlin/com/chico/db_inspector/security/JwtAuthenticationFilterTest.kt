package com.chico.dbinspector.security

import com.chico.dbinspector.ai.AiTokenAuthenticationDetails
import com.chico.dbinspector.ai.AiTokenIdentity
import com.chico.dbinspector.ai.AiTokenService
import com.chico.dbinspector.auth.AppUserDetailsService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.auth.JwtService
import com.chico.dbinspector.auth.KeycloakJwtService
import com.chico.dbinspector.auth.KeycloakLoginRejectedException
import com.chico.dbinspector.auth.KeycloakUserService
import com.chico.dbinspector.config.DbInspectorProperties
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class JwtAuthenticationFilterTest {

    private val jwtService = Mockito.mock(JwtService::class.java)
    private val userDetailsService = Mockito.mock(AppUserDetailsService::class.java)
    private val aiTokenService = Mockito.mock(AiTokenService::class.java)
    private val keycloakJwtService = Mockito.mock(KeycloakJwtService::class.java)
    private val keycloakUserService = Mockito.mock(KeycloakUserService::class.java)
    private val properties = DbInspectorProperties().apply { keycloak.enabled = true }
    private val filter = JwtAuthenticationFilter(
        jwtService, userDetailsService, aiTokenService, keycloakJwtService, keycloakUserService, properties
    )
    private val keycloakToken = "eyJhbGciOiJSUzI1NiJ9.corpo.assinatura"
    private val claims = Jwts.claims().subject("kc-ana-0001").build()

    private val token = "rpt_abc"
    private val principal = AuthUserPrincipal(
        userId = UUID.randomUUID(),
        name = "IA: teste",
        email = "ana@example.com",
        passwordHash = "",
        active = true,
        authoritiesList = listOf("REPORT_READ", "ROLE_AI_AGENT")
    )

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `token de IA fora de api db ai devolve 403 sem consultar o token`() {
        val (response, chain) = run("/api/db/reports")

        assertEquals(403, response.status)
        assertNull(chain.request)
        Mockito.verifyNoInteractions(aiTokenService)
    }

    @Test
    fun `token de IA nao gerencia tokens`() {
        val (response, chain) = run("/api/db/ai/tokens")

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun `token de IA invalido devolve 401 com mensagem legivel`() {
        Mockito.`when`(aiTokenService.authenticate(token)).thenReturn(null)

        val (response, chain) = run("/api/db/ai/relatorios")

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("invalido, expirado ou revogado"))
        assertNull(chain.request)
    }

    @Test
    fun `token de IA valido autentica dentro de api db ai`() {
        val tokenId = UUID.randomUUID()
        Mockito.`when`(aiTokenService.authenticate(token)).thenReturn(AiTokenIdentity(principal, tokenId, "teste"))

        val (response, chain) = run("/api/db/ai/relatorios")

        assertEquals(200, response.status)
        assertTrue(chain.request != null)
        assertSame(principal, SecurityContextHolder.getContext().authentication?.principal)
        assertEquals(
            AiTokenAuthenticationDetails(tokenId, "teste"),
            SecurityContextHolder.getContext().authentication?.details
        )
        Mockito.verifyNoInteractions(jwtService)
    }

    @Test
    fun `token do Keycloak valido autentica com o principal do espelho local`() {
        Mockito.`when`(keycloakJwtService.isKeycloakToken(keycloakToken)).thenReturn(true)
        Mockito.`when`(keycloakJwtService.validate(keycloakToken)).thenReturn(claims)
        Mockito.`when`(keycloakUserService.principalFor(claims)).thenReturn(principal)

        val (response, chain) = run("/api/db/reports", keycloakToken)

        assertEquals(200, response.status)
        assertTrue(chain.request != null)
        assertSame(principal, SecurityContextHolder.getContext().authentication?.principal)
        Mockito.verifyNoInteractions(jwtService)
    }

    @Test
    fun `token do Keycloak recusado na validacao devolve 401`() {
        Mockito.`when`(keycloakJwtService.isKeycloakToken(keycloakToken)).thenReturn(true)
        Mockito.`when`(keycloakJwtService.validate(keycloakToken)).thenThrow(IllegalArgumentException("Token emitido para outro client"))

        val (response, chain) = run("/api/db/reports", keycloakToken)

        assertEquals(401, response.status)
        assertNull(chain.request)
        Mockito.verifyNoInteractions(keycloakUserService)
    }

    @Test
    fun `pessoa recusada no provisionamento recebe 403 com o motivo`() {
        Mockito.`when`(keycloakJwtService.isKeycloakToken(keycloakToken)).thenReturn(true)
        Mockito.`when`(keycloakJwtService.validate(keycloakToken)).thenReturn(claims)
        Mockito.`when`(keycloakUserService.principalFor(claims))
            .thenThrow(KeycloakLoginRejectedException("O Keycloak nao confirmou o e-mail desta conta"))

        val (response, chain) = run("/api/db/reports", keycloakToken)

        assertEquals(403, response.status)
        assertTrue(response.contentAsString.contains("nao confirmou o e-mail"))
        assertNull(chain.request)
    }

    @Test
    fun `primeiro acesso simultaneo repete a busca depois de perder na chave unica`() {
        Mockito.`when`(keycloakJwtService.isKeycloakToken(keycloakToken)).thenReturn(true)
        Mockito.`when`(keycloakJwtService.validate(keycloakToken)).thenReturn(claims)
        Mockito.`when`(keycloakUserService.principalFor(claims))
            .thenThrow(DataIntegrityViolationException("uk_users_keycloak_id"))
            .thenReturn(principal)

        val (response, chain) = run("/api/db/reports", keycloakToken)

        assertEquals(200, response.status)
        assertTrue(chain.request != null)
    }

    @Test
    fun `com Keycloak ligado o token local e recusado sem ser lido`() {
        val localToken = "eyJhbGciOiJIUzI1NiJ9.corpo.assinatura"

        val (response, chain) = run("/api/db/reports", localToken)

        assertEquals(401, response.status)
        assertNull(chain.request)
        Mockito.verifyNoInteractions(jwtService)
    }

    private fun run(path: String, bearer: String = token): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", path).apply {
            servletPath = path
            addHeader("Authorization", "Bearer $bearer")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }
}
