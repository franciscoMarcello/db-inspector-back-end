package com.chico.dbinspector.auth

import jakarta.transaction.Transactional
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val authenticationManager: AuthenticationManager
) {
    @Transactional
    fun login(request: LoginRequest): AuthResponse {
        val auth = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(
                request.email.trim().lowercase(),
                request.password
            )
        )
        val principal = auth.principal as AuthUserPrincipal
        val user = userRepository.findById(principal.userId).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais invalidas")
        }
        return issueTokens(user)
    }

    @Transactional
    fun refresh(request: RefreshRequest): AuthResponse {
        val tokenHash = jwtService.hashRefreshToken(request.refreshToken.trim())
        val entity = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token invalido")
        }

        if (entity.expiresAt.isBefore(Instant.now())) {
            entity.revoked = true
            refreshTokenRepository.save(entity)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expirado")
        }

        val user = entity.user ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario invalido")
        if (!user.active) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario inativo")
        }

        entity.revoked = true
        refreshTokenRepository.save(entity)
        return issueTokens(user)
    }

    @Transactional
    fun logout(request: LogoutRequest) {
        val tokenHash = jwtService.hashRefreshToken(request.refreshToken.trim())
        val token = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash).orElse(null) ?: return
        token.revoked = true
        refreshTokenRepository.save(token)
    }

    fun me(authentication: Authentication): AuthUserResponse {
        val principal = authentication.principal as? AuthUserPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado")
        return toAuthUserResponse(principal.userId, principal.name, principal.username)
    }

    @Transactional
    fun issueTokens(user: AppUserEntity): AuthResponse {
        val userId = user.id ?: error("Usuario sem id")
        refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId).forEach { it.revoked = true }

        val (accessToken, accessExpiresAt) = jwtService.generateAccessToken(user)
        val refreshPayload = jwtService.generateRefreshToken()

        val refreshEntity = RefreshTokenEntity(
            user = user,
            tokenHash = refreshPayload.tokenHash,
            expiresAt = refreshPayload.expiresAt,
            revoked = false
        )
        refreshTokenRepository.save(refreshEntity)

        val expiresIn = accessExpiresAt.epochSecond - Instant.now().epochSecond

        return AuthResponse(
            accessToken = accessToken,
            expiresInSeconds = expiresIn.coerceAtLeast(0),
            refreshToken = refreshPayload.rawToken,
            user = toAuthUserResponse(userId, user.name, user.email)
        )
    }

    private fun toAuthUserResponse(userId: UUID, name: String?, email: String): AuthUserResponse {
        val roleNames = userRoleRepository.findRoleNamesByUserId(userId)
            .map { it.uppercase() }
            .distinct()
            .sorted()

        val roleIds = userRoleRepository.findRoleIdsByUserId(userId).toSet()
        val permissions = if (roleIds.isEmpty()) {
            emptyList()
        } else {
            rolePermissionRepository.findPermissionCodesByRoleIds(roleIds)
                .map { it.uppercase() }
                .distinct()
                .sorted()
        }

        return AuthUserResponse(
            id = userId.toString(),
            name = name?.trim().takeUnless { it.isNullOrBlank() } ?: "",
            email = email,
            roles = roleNames,
            permissions = permissions
        )
    }
}

@Component
class AppUserDetailsService(
    private val userRepository: AppUserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository
) : org.springframework.security.core.userdetails.UserDetailsService {
    override fun loadUserByUsername(username: String): org.springframework.security.core.userdetails.UserDetails {
        val user = userRepository.findByEmailIgnoreCase(username.trim()).orElseThrow {
            org.springframework.security.core.userdetails.UsernameNotFoundException("Usuario nao encontrado")
        }
        val userId = user.id ?: error("Usuario sem id")
        val roleNames = userRoleRepository.findRoleNamesByUserId(userId)
        val roleAuthorities = roleNames.map { "ROLE_${it.uppercase()}" }

        val roleIds = userRoleRepository.findRoleIdsByUserId(userId).toSet()
        val permissionAuthorities = if (roleIds.isEmpty()) {
            emptyList()
        } else {
            rolePermissionRepository.findPermissionCodesByRoleIds(roleIds)
        }

        return AuthUserPrincipal(
            userId = userId,
            name = user.name?.trim().takeUnless { it.isNullOrBlank() } ?: "",
            email = user.email,
            passwordHash = user.passwordHash,
            active = user.active,
            authoritiesList = roleAuthorities + permissionAuthorities
        )
    }
}

@Component
class AuthBootstrap(
    private val userRepository: AppUserRepository,
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val passwordEncoder: PasswordEncoder,
    @org.springframework.beans.factory.annotation.Value("\${dbinspector.auth.bootstrap-admin.email:}")
    private val adminEmail: String,
    @org.springframework.beans.factory.annotation.Value("\${dbinspector.auth.bootstrap-admin.password:}")
    private val adminPassword: String
) : org.springframework.boot.CommandLineRunner {
    @Transactional
    override fun run(vararg args: String?) {
        val permissions = PermissionCodes.all.map { code ->
            permissionRepository.findByCode(code)
                .orElseGet { permissionRepository.save(PermissionEntity(code = code)) }
        }
        val permissionByCode = permissions.associateBy { it.code.uppercase() }

        val adminRole = ensureRoleWithPermissions(
            roleName = "ADMIN",
            permissionCodes = PermissionCodes.all,
            permissionByCode = permissionByCode
        )

        ensureRoleWithPermissions(
            roleName = "USER",
            permissionCodes = listOf(
                PermissionCodes.REPORT_READ,
                PermissionCodes.REPORT_RUN,
                PermissionCodes.FOLDER_READ,
                PermissionCodes.TEMPLATE_READ,
                PermissionCodes.SQL_METADATA_READ,
                PermissionCodes.EMAIL_SEND,
                PermissionCodes.EMAIL_TEST,
                PermissionCodes.EMAIL_SCHEDULE_READ
            ),
            permissionByCode = permissionByCode
        )

        val email = adminEmail.trim().lowercase()
        val password = adminPassword.trim()
        if (email.isBlank() || password.isBlank()) return

        val user = userRepository.findByEmailIgnoreCase(email).orElseGet {
            userRepository.save(
                AppUserEntity(
                    name = "Administrador",
                    email = email,
                    passwordHash = passwordEncoder.encode(password),
                    active = true
                )
            )
        }

        val userId = user.id ?: error("Usuario sem id")
        val roleId = adminRole.id ?: error("Role sem id")
        if (!userRoleRepository.existsByUserIdAndRoleId(userId, roleId)) {
            userRoleRepository.save(
                UserRoleEntity(
                    user = user,
                    role = adminRole
                )
            )
        }
    }

    private fun ensureRoleWithPermissions(
        roleName: String,
        permissionCodes: List<String>,
        permissionByCode: Map<String, PermissionEntity>
    ): RoleEntity {
        val role = roleRepository.findByNameIgnoreCase(roleName)
            .orElseGet { roleRepository.save(RoleEntity(name = roleName.uppercase())) }
        val roleId = role.id ?: error("Role sem id")

        permissionCodes.forEach { code ->
            val permission = permissionByCode[code.uppercase()]
                ?: error("Permissao '$code' nao encontrada")
            val permissionId = permission.id ?: error("Permissao sem id")
            if (!rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permissionId)) {
                rolePermissionRepository.save(
                    RolePermissionEntity(
                        role = role,
                        permission = permission
                    )
                )
            }
        }

        return role
    }
}
