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

        return AuthUserResponse(
            id = principal.userId.toString(),
            email = principal.username
        )
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
            user = AuthUserResponse(
                id = userId.toString(),
                email = user.email
            )
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
        val adminRole = roleRepository.findByNameIgnoreCase("ADMIN")
            .orElseGet { roleRepository.save(RoleEntity(name = "ADMIN")) }

        val permissions = PermissionCodes.all.map { code ->
            permissionRepository.findByCode(code)
                .orElseGet { permissionRepository.save(PermissionEntity(code = code)) }
        }
        permissions.forEach { permission ->
            val permissionId = permission.id ?: error("Permissao sem id")
            val roleId = adminRole.id ?: error("Role sem id")
            if (!rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, permissionId)) {
                rolePermissionRepository.save(
                    RolePermissionEntity(
                        role = adminRole,
                        permission = permission
                    )
                )
            }
        }

        val email = adminEmail.trim().lowercase()
        val password = adminPassword.trim()
        if (email.isBlank() || password.isBlank()) return

        val user = userRepository.findByEmailIgnoreCase(email).orElseGet {
            userRepository.save(
                AppUserEntity(
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
}
