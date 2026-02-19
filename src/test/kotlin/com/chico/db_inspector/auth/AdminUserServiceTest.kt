package com.chico.dbinspector.auth

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant
import java.util.Optional
import java.util.UUID

class AdminUserServiceTest {

    @Test
    fun `setUserActive should revoke refresh tokens when disabling user`() {
        val userRepository = Mockito.mock(AppUserRepository::class.java)
        val roleRepository = Mockito.mock(RoleRepository::class.java)
        val permissionRepository = Mockito.mock(PermissionRepository::class.java)
        val userRoleRepository = Mockito.mock(UserRoleRepository::class.java)
        val rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java)
        val refreshTokenRepository = Mockito.mock(RefreshTokenRepository::class.java)
        val auditService = Mockito.mock(AdminAuditService::class.java)

        val service = AdminUserService(
            userRepository = userRepository,
            roleRepository = roleRepository,
            permissionRepository = permissionRepository,
            userRoleRepository = userRoleRepository,
            rolePermissionRepository = rolePermissionRepository,
            refreshTokenRepository = refreshTokenRepository,
            auditService = auditService,
            passwordEncoder = BCryptPasswordEncoder()
        )

        val userId = UUID.randomUUID()
        val user = AppUserEntity(
            id = userId,
            email = "user@test.com",
            passwordHash = "hash",
            active = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val token = RefreshTokenEntity(
            id = UUID.randomUUID(),
            user = user,
            tokenHash = "tokenhash",
            expiresAt = Instant.now().plusSeconds(3600),
            revoked = false
        )

        Mockito.`when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        Mockito.`when`(refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId)).thenReturn(listOf(token))
        Mockito.`when`(userRepository.save(Mockito.any(AppUserEntity::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(userRoleRepository.findRoleNamesByUserId(userId)).thenReturn(emptyList())

        service.setUserActive(userId, false)

        assertTrue(token.revoked)
        Mockito.verify(refreshTokenRepository).save(token)
    }

    @Test
    fun `resetPassword should update hash and revoke refresh tokens`() {
        val userRepository = Mockito.mock(AppUserRepository::class.java)
        val roleRepository = Mockito.mock(RoleRepository::class.java)
        val permissionRepository = Mockito.mock(PermissionRepository::class.java)
        val userRoleRepository = Mockito.mock(UserRoleRepository::class.java)
        val rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java)
        val refreshTokenRepository = Mockito.mock(RefreshTokenRepository::class.java)
        val auditService = Mockito.mock(AdminAuditService::class.java)
        val passwordEncoder = BCryptPasswordEncoder()

        val service = AdminUserService(
            userRepository = userRepository,
            roleRepository = roleRepository,
            permissionRepository = permissionRepository,
            userRoleRepository = userRoleRepository,
            rolePermissionRepository = rolePermissionRepository,
            refreshTokenRepository = refreshTokenRepository,
            auditService = auditService,
            passwordEncoder = passwordEncoder
        )

        val userId = UUID.randomUUID()
        val user = AppUserEntity(
            id = userId,
            email = "user@test.com",
            passwordHash = passwordEncoder.encode("senha-antiga"),
            active = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val token = RefreshTokenEntity(
            id = UUID.randomUUID(),
            user = user,
            tokenHash = "tokenhash",
            expiresAt = Instant.now().plusSeconds(3600),
            revoked = false
        )

        Mockito.`when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        Mockito.`when`(refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId)).thenReturn(listOf(token))
        Mockito.`when`(userRepository.save(Mockito.any(AppUserEntity::class.java))).thenAnswer { it.arguments[0] }

        service.resetPassword(userId, "nova-senha-123")

        assertTrue(passwordEncoder.matches("nova-senha-123", user.passwordHash))
        assertTrue(token.revoked)
        Mockito.verify(refreshTokenRepository).save(token)
    }
}
