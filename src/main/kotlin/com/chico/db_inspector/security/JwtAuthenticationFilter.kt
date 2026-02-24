package com.chico.dbinspector.security

import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.auth.JwtService
import com.chico.dbinspector.auth.AppUserDetailsService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: AppUserDetailsService
) : OncePerRequestFilter() {

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
}
