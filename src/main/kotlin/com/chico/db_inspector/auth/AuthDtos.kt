package com.chico.dbinspector.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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
    val name: String,
    val email: String,
    val roles: List<String>,
    val permissions: List<String>
)

data class AuthResponse(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val user: AuthUserResponse
)

data class AdminCreateUserRequest(
    @field:NotBlank
    val name: String,
    @field:Email
    @field:NotBlank
    val email: String,
    @field:NotBlank
    val password: String,
    val active: Boolean = true,
    @field:Size(min = 1)
    val roles: List<String>
)

data class AdminSetUserActiveRequest(
    val active: Boolean
)

data class AdminSetUserNameRequest(
    @field:NotBlank
    val name: String
)

data class AdminResetPasswordRequest(
    @field:NotBlank
    val password: String
)

data class AdminAssignRoleRequest(
    @field:NotBlank
    val role: String
)

data class AdminUserResponse(
    val id: String,
    val name: String,
    val email: String,
    val active: Boolean,
    val roles: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)

data class AdminRoleResponse(
    val name: String,
    val permissions: List<String>
)

data class AdminCreateRoleRequest(
    @field:NotBlank
    val name: String,
    val permissions: List<String> = emptyList()
)

data class AdminUpdateRoleRequest(
    val name: String? = null,
    val permissions: List<String>? = null
)
