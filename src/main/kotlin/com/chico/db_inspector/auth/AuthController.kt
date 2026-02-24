package com.chico.dbinspector.auth

import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, servletRequest: HttpServletRequest): AuthResponse {
        val forwarded = servletRequest.getHeader("X-Forwarded-For")
        val clientIp = forwarded?.split(",")?.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
            ?: servletRequest.remoteAddr
            ?: "unknown"
        return authService.login(request, clientIp)
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthResponse = authService.refresh(request)

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: LogoutRequest): ResponseEntity<Void> {
        authService.logout(request)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): AuthUserResponse = authService.me(authentication)
}
