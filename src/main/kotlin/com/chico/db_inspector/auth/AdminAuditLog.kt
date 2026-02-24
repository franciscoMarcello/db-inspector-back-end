package com.chico.dbinspector.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(name = "actor_user_id")
    var actorUserId: UUID? = null,
    @Column(name = "actor_email")
    var actorEmail: String? = null,
    @Column(nullable = false, length = 80)
    var action: String = "",
    @Column(name = "target_type", nullable = false, length = 80)
    var targetType: String = "",
    @Column(name = "target_id", length = 120)
    var targetId: String? = null,
    @Column(columnDefinition = "TEXT")
    var details: String? = null,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null
)
