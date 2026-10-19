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

/** Publico: diz ao front como entrar. Nada aqui e segredo. */
data class AuthConfigResponse(
    val localLoginEnabled: Boolean,
    val keycloak: KeycloakClientConfig?
)

data class KeycloakClientConfig(
    val url: String,
    val realm: String,
    val clientId: String
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
    @field:Size(min = 8)
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
    @field:Size(min = 8)
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
    /** Entra pelo Keycloak: nome, e-mail e perfis vem de la. */
    val sso: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class AdminRoleResponse(
    val name: String,
    val permissions: List<String>,
    /** null com a sincronizacao de perfis desligada. */
    val keycloak: KeycloakRoleStatus? = null
)

/** O perfil como role do client de login no Keycloak. */
enum class KeycloakRoleStatus {
    /** Role criada pelo agroreports: a tela cria, renomeia e exclui. */
    SYNCED,

    /** Existe, mas foi criada a mao no console (ex.: ADMIN): a tela nao renomeia nem exclui. */
    MANUAL,

    /** Nao existe no client: ninguem recebe o perfil pelo SSO. Salvar o perfil cria a role. */
    MISSING,

    /** O Keycloak nao respondeu agora. */
    UNAVAILABLE
}

data class AdminCreateRoleRequest(
    @field:NotBlank
    val name: String,
    val permissions: List<String> = emptyList()
)

data class AdminUpdateRoleRequest(
    val name: String? = null,
    val permissions: List<String>? = null
)
