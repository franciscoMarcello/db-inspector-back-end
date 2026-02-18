package com.chico.dbinspector.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${dbinspector.auth.jwt.secret}") jwtSecret: String,
    @Value("\${dbinspector.auth.jwt.access-ttl-minutes:15}")
    private val accessTtlMinutes: Long,
    @Value("\${dbinspector.auth.refresh-ttl-days:7}")
    private val refreshTtlDays: Long
) {
    private val secretKey: SecretKey = toSecretKey(jwtSecret)
    private val secureRandom = SecureRandom()

    fun generateAccessToken(user: AppUserEntity): Pair<String, Instant> {
        val userId = user.id ?: error("user id ausente")
        val now = Instant.now()
        val expiresAt = now.plusSeconds(accessTtlMinutes * 60)

        val token = Jwts.builder()
            .subject(user.email)
            .claim("uid", userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(secretKey)
            .compact()

        return token to expiresAt
    }

    fun parseAccessToken(token: String): Claims =
        Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload

    fun generateRefreshToken(): RefreshTokenPayload {
        val raw = randomToken()
        val expiresAt = Instant.now().plusSeconds(refreshTtlDays * 24 * 60 * 60)
        return RefreshTokenPayload(
            rawToken = raw,
            tokenHash = sha256Hex(raw),
            expiresAt = expiresAt
        )
    }

    fun hashRefreshToken(rawToken: String): String = sha256Hex(rawToken)

    private fun randomToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun toSecretKey(rawSecret: String): SecretKey {
        val normalized = rawSecret.trim()
        require(normalized.isNotBlank()) { "dbinspector.auth.jwt.secret nao pode ser vazio" }

        val bytes = runCatching { Decoders.BASE64.decode(normalized) }
            .getOrElse { normalized.toByteArray(Charsets.UTF_8) }

        require(bytes.size >= 32) { "JWT secret precisa de pelo menos 32 bytes" }
        return Keys.hmacShaKeyFor(bytes)
    }
}

data class RefreshTokenPayload(
    val rawToken: String,
    val tokenHash: String,
    val expiresAt: Instant
)

data class AuthUserPrincipal(
    val userId: UUID,
    private val email: String,
    private val passwordHash: String,
    private val active: Boolean,
    private val authoritiesList: Collection<String>
) : org.springframework.security.core.userdetails.UserDetails {
    override fun getAuthorities(): MutableCollection<out org.springframework.security.core.GrantedAuthority> =
        authoritiesList
            .map { org.springframework.security.core.authority.SimpleGrantedAuthority(it) }
            .toMutableList()

    override fun getPassword(): String = passwordHash
    override fun getUsername(): String = email
    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = active
}
