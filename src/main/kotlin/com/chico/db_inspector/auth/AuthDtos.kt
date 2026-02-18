package com.chico.dbinspector.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:Email
    @field:NotBlank
    val email: String,
    @field:NotBlank
    val password: String
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String
)

data class LogoutRequest(
    @field:NotBlank
    val refreshToken: String
)

data class AuthUserResponse(
    val id: String,
    val email: String
)

data class AuthResponse(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val user: AuthUserResponse
)
