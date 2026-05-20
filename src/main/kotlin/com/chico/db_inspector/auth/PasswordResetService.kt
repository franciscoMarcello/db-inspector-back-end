package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@Service
class PasswordResetService(
    private val userRepository: AppUserRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val adminAuditService: AdminAuditService,
    private val mailSender: JavaMailSender,
    private val props: DbInspectorProperties
) {
    private val log = LoggerFactory.getLogger(PasswordResetService::class.java)
    private val secureRandom = SecureRandom()

    private companion object {
        const val TOKEN_TTL_MINUTES = 60L
    }

    @Transactional
    fun requestReset(email: String) {
        val normalizedEmail = email.trim().lowercase()
        val user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null)

        if (user == null || !user.active) {
            log.info("Solicitacao de redefinicao de senha para email nao encontrado ou inativo: {}", normalizedEmail)
            return
        }

        val userId = user.id ?: return

        passwordResetTokenRepository.findAllByUserIdAndUsedFalse(userId).forEach { it.used = true }

        val rawToken = generateToken()
        val tokenHash = sha256Hex(rawToken)
        val expiresAt = Instant.now().plusSeconds(TOKEN_TTL_MINUTES * 60)

        passwordResetTokenRepository.save(
            PasswordResetTokenEntity(
                user = user,
                tokenHash = tokenHash,
                expiresAt = expiresAt,
                used = false
            )
        )

        sendResetEmail(user.email, rawToken)

        adminAuditService.log(
            action = "AUTH_PASSWORD_RESET_REQUESTED",
            targetType = "USER",
            targetId = userId.toString(),
            details = mapOf("email" to normalizedEmail)
        )
    }

    @Transactional
    fun confirmReset(rawToken: String, newPassword: String) {
        PasswordPolicy.validateOrThrow(newPassword)

        val tokenHash = sha256Hex(rawToken.trim())
        val tokenEntity = passwordResetTokenRepository.findByTokenHashAndUsedFalse(tokenHash).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalido ou expirado")
        }

        if (tokenEntity.expiresAt.isBefore(Instant.now())) {
            tokenEntity.used = true
            passwordResetTokenRepository.save(tokenEntity)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalido ou expirado")
        }

        val user = tokenEntity.user ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalido ou expirado")
        if (!user.active) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario inativo")
        }

        user.passwordHash = passwordEncoder.encode(newPassword)
        userRepository.save(user)

        tokenEntity.used = true
        passwordResetTokenRepository.save(tokenEntity)

        val userId = user.id ?: return
        refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId).forEach { it.revoked = true }

        adminAuditService.log(
            action = "AUTH_PASSWORD_RESET_CONFIRMED",
            targetType = "USER",
            targetId = userId.toString(),
            details = mapOf("email" to user.email)
        )
    }

    private fun sendResetEmail(to: String, rawToken: String) {
        val resetUrl = props.mail.passwordResetUrl.trimEnd('/')
        val body = if (resetUrl.isNotBlank()) {
            val link = "$resetUrl?token=$rawToken"
            """
            Voce solicitou a redefinicao de senha.

            Clique no link abaixo para criar uma nova senha (valido por $TOKEN_TTL_MINUTES minutos):

            $link

            Se nao foi voce, ignore este email.
            """.trimIndent()
        } else {
            """
            Voce solicitou a redefinicao de senha.

            Use o token abaixo para criar uma nova senha (valido por $TOKEN_TTL_MINUTES minutos):

            $rawToken

            Se nao foi voce, ignore este email.
            """.trimIndent()
        }

        val message = SimpleMailMessage()
        message.from = props.mail.from
        message.setTo(to)
        message.subject = "Redefinicao de senha - DB Inspector"
        message.text = body
        mailSender.send(message)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
