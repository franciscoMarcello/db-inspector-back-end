package com.chico.dbinspector.security

import com.chico.dbinspector.ai.AiTokenAuthenticationDetails
import com.chico.dbinspector.ai.AiTokenIdentity
import com.chico.dbinspector.ai.AiTokenService
import com.chico.dbinspector.auth.AppUserDetailsService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.auth.JwtService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class JwtAuthenticationFilterTest {

    private val jwtService = Mockito.mock(JwtService::class.java)
    private val userDetailsService = Mockito.mock(AppUserDetailsService::class.java)
    private val aiTokenService = Mockito.mock(AiTokenService::class.java)
    private val filter = JwtAuthenticationFilter(jwtService, userDetailsService, aiTokenService)

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

    private fun run(path: String): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", path).apply {
            servletPath = path
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }
}
