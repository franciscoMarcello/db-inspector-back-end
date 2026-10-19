package com.chico.dbinspector.ai

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class AiApprovalStatus {
    PENDING,
    APPROVED,
    DENIED
}

/**
 * Pedido da IA para passar dos limites (linhas ou custo) em UMA consulta especifica. Vale so para o
 * token que pediu e para aquele SQL, ate expirar. Nunca libera schema, funcao ou escrita.
 */
@Entity
@Table(
    name = "ai_query_approvals",
    indexes = [
        Index(name = "idx_ai_approvals_token_hash", columnList = "token_id, sql_hash"),
        Index(name = "idx_ai_approvals_status", columnList = "status")
    ]
)
class AiQueryApprovalEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(name = "token_id", nullable = false)
    var tokenId: UUID? = null,
    @Column(name = "token_name", nullable = false)
    var tokenName: String = "",
    @Column(name = "requested_by", nullable = false)
    var requestedBy: String = "",
    @Column(name = "sql_hash", nullable = false, length = 64)
    var sqlHash: String = "",
    @Column(nullable = false, columnDefinition = "TEXT")
    var sql: String = "",
    @Column(nullable = false, columnDefinition = "TEXT")
    var reason: String = "",
    @Column(name = "requested_rows", nullable = false)
    var requestedRows: Int = 0,
    @Column(name = "estimated_cost")
    var estimatedCost: Double? = null,
    @Column(nullable = false, length = 16)
    var status: String = AiApprovalStatus.PENDING.name,
    @Column(name = "approved_rows")
    var approvedRows: Int? = null,
    @Column(name = "decided_by")
    var decidedBy: String? = null,
    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
    @Column(name = "decision_note", columnDefinition = "TEXT")
    var decisionNote: String? = null,
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null
)

interface AiQueryApprovalRepository : JpaRepository<AiQueryApprovalEntity, UUID> {
    fun findAllByTokenIdAndSqlHashOrderByCreatedAtDesc(tokenId: UUID, sqlHash: String): List<AiQueryApprovalEntity>
    fun findAllByStatusOrderByCreatedAtDesc(status: String): List<AiQueryApprovalEntity>
    fun findTop100ByOrderByCreatedAtDesc(): List<AiQueryApprovalEntity>
    fun countByStatus(status: String): Long
}
