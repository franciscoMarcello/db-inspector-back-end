package com.chico.dbinspector.ai

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * Token para agentes de IA. O valor so existe na resposta da criacao: aqui fica apenas o SHA-256,
 * entao um backup vazado nao entrega token nenhum.
 */
@Entity
@Table(name = "ai_tokens")
class AiTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false)
    var name: String = "",
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String = "",
    @Column(nullable = false, length = 16)
    var prefix: String = "",
    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID? = null,
    @Column(name = "created_by_email", nullable = false)
    var createdByEmail: String = "",
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Column(name = "revoked_by")
    var revokedBy: String? = null,
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
)
