package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.server.ResponseStatusException
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
        val roleManagementService = Mockito.mock(AdminRoleManagementService::class.java)

        val service = AdminUserService(
            userRepository = userRepository,
            roleRepository = roleRepository,
            permissionRepository = permissionRepository,
            userRoleRepository = userRoleRepository,
            rolePermissionRepository = rolePermissionRepository,
            refreshTokenRepository = refreshTokenRepository,
            auditService = auditService,
            roleManagementService = roleManagementService,
            passwordEncoder = BCryptPasswordEncoder(),
            properties = DbInspectorProperties()
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
        val roleManagementService = Mockito.mock(AdminRoleManagementService::class.java)
        val passwordEncoder = BCryptPasswordEncoder()

        val service = AdminUserService(
            userRepository = userRepository,
            roleRepository = roleRepository,
            permissionRepository = permissionRepository,
            userRoleRepository = userRoleRepository,
            rolePermissionRepository = rolePermissionRepository,
            refreshTokenRepository = refreshTokenRepository,
            auditService = auditService,
            roleManagementService = roleManagementService,
            passwordEncoder = passwordEncoder,
            properties = DbInspectorProperties()
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

        service.resetPassword(userId, "Nova-senha-123")

        assertTrue(passwordEncoder.matches("Nova-senha-123", user.passwordHash))
        assertTrue(token.revoked)
        Mockito.verify(refreshTokenRepository).save(token)
    }

    @Test
    fun `usuario do Keycloak nao tem perfil, nome nem senha alterados pela tela`() {
        val fixture = Fixture()
        val userId = UUID.randomUUID()
        val user = AppUserEntity(id = userId, email = "ana@test.com", keycloakId = "kc-ana-0001", active = true)
        Mockito.`when`(fixture.userRepository.findById(userId)).thenReturn(Optional.of(user))

        val attempts = listOf<() -> Unit>(
            { fixture.service.assignRole(userId, "ANALISTA") },
            { fixture.service.removeRole(userId, "ANALISTA") },
            { fixture.service.setUserName(userId, "Outro nome") },
            { fixture.service.resetPassword(userId, "Nova-senha-123") }
        )
        attempts.forEach { attempt ->
            val error = assertThrows(ResponseStatusException::class.java) { attempt() }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }
        Mockito.verify(fixture.userRoleRepository, Mockito.never()).save(Mockito.any(UserRoleEntity::class.java))
        Mockito.verify(fixture.userRepository, Mockito.never()).save(Mockito.any(AppUserEntity::class.java))
    }

    @Test
    fun `com Keycloak ligado nao cria usuario com senha`() {
        val fixture = Fixture(DbInspectorProperties().apply { keycloak.enabled = true })

        val error = assertThrows(ResponseStatusException::class.java) {
            fixture.service.createUser(
                AdminCreateUserRequest(name = "Ana", email = "ana@test.com", password = "Senha-forte-123", roles = listOf("ANALISTA"))
            )
        }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        Mockito.verifyNoInteractions(fixture.userRepository)
    }

    private class Fixture(properties: DbInspectorProperties = DbInspectorProperties()) {
        val userRepository: AppUserRepository = Mockito.mock(AppUserRepository::class.java)
        val userRoleRepository: UserRoleRepository = Mockito.mock(UserRoleRepository::class.java)
        val service = AdminUserService(
            userRepository = userRepository,
            roleRepository = Mockito.mock(RoleRepository::class.java),
            permissionRepository = Mockito.mock(PermissionRepository::class.java),
            userRoleRepository = userRoleRepository,
            rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java),
            refreshTokenRepository = Mockito.mock(RefreshTokenRepository::class.java),
            auditService = Mockito.mock(AdminAuditService::class.java),
            roleManagementService = Mockito.mock(AdminRoleManagementService::class.java),
            passwordEncoder = BCryptPasswordEncoder(),
            properties = properties
        )
    }
}
