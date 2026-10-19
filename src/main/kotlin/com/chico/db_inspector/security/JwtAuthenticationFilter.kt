package com.chico.dbinspector.security

import com.chico.dbinspector.ai.AiTokenAuthenticationDetails
import com.chico.dbinspector.ai.AiTokenService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.auth.JwtService
import com.chico.dbinspector.auth.AppUserDetailsService
import com.chico.dbinspector.auth.KeycloakJwtService
import com.chico.dbinspector.auth.KeycloakLoginRejectedException
import com.chico.dbinspector.auth.KeycloakUserService
import com.chico.dbinspector.config.DbInspectorProperties
import io.jsonwebtoken.Claims
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: AppUserDetailsService,
    private val aiTokenService: AiTokenService,
    private val keycloakJwtService: KeycloakJwtService,
    private val keycloakUserService: KeycloakUserService,
    private val properties: DbInspectorProperties
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    companion object {
        const val AI_PATH_PREFIX = "/api/db/ai/"
        const val AI_TOKENS_PATH = "/api/db/ai/tokens"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header.isNullOrBlank() || !header.startsWith("Bearer ", ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        val token = header.substring(7).trim()
        if (token.isEmpty() || SecurityContextHolder.getContext().authentication != null) {
            filterChain.doFilter(request, response)
            return
        }

        // Token de IA nao e JWT: e validado aqui, antes do parse, que recusaria o valor com 401.
        if (token.startsWith(AiTokenService.TOKEN_PREFIX)) {
            authenticateAiToken(token, request, response, filterChain)
            return
        }

        if (properties.keycloak.enabled && keycloakJwtService.isKeycloakToken(token)) {
            authenticateKeycloak(token, request, response, filterChain)
            return
        }

        // Token local (HS256): so vale com o Keycloak desligado.
        if (!properties.localLoginEnabled()) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }

        val authResult = runCatching {
            val claims = jwtService.parseAccessToken(token)
            val email = claims.subject ?: return@runCatching
            val userDetails = userDetailsService.loadUserByUsername(email) as AuthUserPrincipal
            if (!userDetails.isEnabled) return@runCatching

            val auth = UsernamePasswordAuthenticationToken(userDetails, null, userDetails.authorities)
            auth.details = WebAuthenticationDetailsSource().buildDetails(request)
            SecurityContextHolder.getContext().authentication = auth
        }
        if (authResult.isFailure) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Token de IA so entra em /api/db/ai/... e nunca na gestao de tokens. A regra e por rota
     * permitida, nao por rota proibida: endpoint novo fora de /ai ja nasce fechado para IA.
     */
    private fun authenticateAiToken(
        token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = UrlPathHelper.defaultInstance.getPathWithinApplication(request)
        if (!path.startsWith(AI_PATH_PREFIX) || path.startsWith(AI_TOKENS_PATH)) {
            writeError(response, HttpStatus.FORBIDDEN, "Token de IA so acessa /api/db/ai e nao gerencia tokens")
            return
        }
        val identity = aiTokenService.authenticate(token)
        if (identity == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Token de IA invalido, expirado ou revogado")
            return
        }
        val auth = UsernamePasswordAuthenticationToken(identity.principal, null, identity.principal.authorities)
        auth.details = AiTokenAuthenticationDetails(identity.tokenId, identity.tokenName)
        SecurityContextHolder.getContext().authentication = auth
        filterChain.doFilter(request, response)
    }

    private fun authenticateKeycloak(
        token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val claims = try {
            keycloakJwtService.validate(token)
        } catch (e: Exception) {
            log.debug("Token do Keycloak recusado: {}", e.message)
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }
        val principal = try {
            resolveKeycloakPrincipal(claims)
        } catch (e: KeycloakLoginRejectedException) {
            writeError(response, HttpStatus.FORBIDDEN, e.message ?: "Acesso negado")
            return
        }
        val auth = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        auth.details = WebAuthenticationDetailsSource().buildDetails(request)
        SecurityContextHolder.getContext().authentication = auth
        filterChain.doFilter(request, response)
    }

    /** Primeiro acesso em duas requisicoes simultaneas: a segunda perde na chave unica e acha o que a primeira gravou. */
    private fun resolveKeycloakPrincipal(claims: Claims): AuthUserPrincipal =
        try {
            keycloakUserService.principalFor(claims)
        } catch (_: DataIntegrityViolationException) {
            keycloakUserService.principalFor(claims)
        }

    private fun writeError(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = "application/json"
        response.writer.write("{\"status\":${status.value()},\"message\":\"$message\"}")
    }
}
