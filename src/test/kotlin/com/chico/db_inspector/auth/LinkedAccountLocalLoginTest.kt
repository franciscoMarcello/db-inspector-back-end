package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Depois do vinculo com o Keycloak, nenhum caminho de senha ou token local vale para a conta. */
class LinkedAccountLocalLoginTest {

    private val properties = DbInspectorProperties()
    private val userRepository = Mockito.mock(AppUserRepository::class.java)
    private val userRoleRepository = Mockito.mock(UserRoleRepository::class.java)
    private val rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java)
    private val refreshTokenRepository = Mockito.mock(RefreshTokenRepository::class.java)
    private val passwordResetTokenRepository = Mockito.mock(PasswordResetTokenRepository::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)
    private val jwtService = Mockito.mock(JwtService::class.java)
    private val authenticationManager = Mockito.mock(AuthenticationManager::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    private val authService = AuthService(
        userRepository, userRoleRepository, rolePermissionRepository, refreshTokenRepository,
        LoginRateLimiter(properties), auditService, jwtService, authenticationManager, properties
    )

    private val linked = AppUserEntity(
        id = UUID.randomUUID(),
        email = "ana@rovema.com.br",
        passwordHash = passwordEncoder.encode("Senha-antiga-123"),
        keycloakId = "kc-ana-0001",
        active = true
    )

    @Test
    fun `conta vinculada nao entra por senha`() {
        Mockito.`when`(userRepository.findByEmailIgnoreCase("ana@rovema.com.br")).thenReturn(Optional.of(linked))

        val error = assertThrows(ResponseStatusException::class.java) {
            authService.login(LoginRequest(email = "Ana@Rovema.com.br", password = "Senha-antiga-123"), "10.0.0.1")
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        Mockito.verifyNoInteractions(authenticationManager)
    }

    @Test
    fun `refresh token emitido antes do vinculo e recusado e revogado`() {
        val refresh = RefreshTokenEntity(
            id = UUID.randomUUID(), user = linked, tokenHash = "hash", expiresAt = Instant.now().plusSeconds(3600)
        )
        Mockito.`when`(jwtService.hashRefreshToken("bruto")).thenReturn("hash")
        Mockito.`when`(refreshTokenRepository.findByTokenHashAndRevokedFalse("hash")).thenReturn(Optional.of(refresh))

        val error = assertThrows(ResponseStatusException::class.java) { authService.refresh(RefreshRequest("bruto")) }

        assertEquals(HttpStatus.UNAUTHORIZED, error.statusCode)
        assertTrue(refresh.revoked)
        Mockito.verify(jwtService, Mockito.never()).generateAccessToken(linked)
    }

    @Test
    fun `token local de conta vinculada nao autentica`() {
        Mockito.`when`(userRepository.findByEmailIgnoreCase("ana@rovema.com.br")).thenReturn(Optional.of(linked))
        val details = AppUserDetailsService(userRepository, userRoleRepository, rolePermissionRepository)

        assertFalse(details.loadUserByUsername("ana@rovema.com.br").isEnabled)
    }

    @Test
    fun `link de redefinicao pedido antes do vinculo nao troca a senha`() {
        val resetService = PasswordResetService(
            userRepository, passwordResetTokenRepository, refreshTokenRepository, passwordEncoder, auditService,
            Mockito.mock(JavaMailSender::class.java), properties
        )
        val oldHash = linked.passwordHash
        val token = PasswordResetTokenEntity(
            id = UUID.randomUUID(), user = linked, tokenHash = "hash", expiresAt = Instant.now().plusSeconds(3600)
        )
        Mockito.`when`(passwordResetTokenRepository.findByTokenHashAndUsedFalse(Mockito.anyString())).thenReturn(Optional.of(token))

        val error = assertThrows(ResponseStatusException::class.java) {
            resetService.confirmReset("token-bruto", "Nova-senha-123")
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        assertEquals(oldHash, linked.passwordHash)
    }

    @Test
    fun `pedido de redefinicao para conta vinculada nao gera link`() {
        val mailSender = Mockito.mock(JavaMailSender::class.java)
        val resetService = PasswordResetService(
            userRepository, passwordResetTokenRepository, refreshTokenRepository, passwordEncoder, auditService,
            mailSender, properties
        )
        Mockito.`when`(userRepository.findByEmailIgnoreCase("ana@rovema.com.br")).thenReturn(Optional.of(linked))

        resetService.requestReset("ana@rovema.com.br")

        Mockito.verifyNoInteractions(passwordResetTokenRepository, mailSender)
    }
}
