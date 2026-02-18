package com.chico.dbinspector.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class AppUserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true)
    var email: String = "",
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",
    @Column(nullable = false)
    var active: Boolean = true,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: Instant? = null
)

@Entity
@Table(name = "roles")
class RoleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true)
    var name: String = ""
)

@Entity
@Table(name = "permissions")
class PermissionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true)
    var code: String = ""
)

@Entity
@Table(
    name = "user_roles",
    uniqueConstraints = [UniqueConstraint(name = "uk_user_roles_user_role", columnNames = ["user_id", "role_id"])],
    indexes = [Index(name = "idx_user_roles_user", columnList = "user_id"), Index(name = "idx_user_roles_role", columnList = "role_id")]
)
class UserRoleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUserEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    var role: RoleEntity? = null
)

@Entity
@Table(
    name = "role_permissions",
    uniqueConstraints = [UniqueConstraint(name = "uk_role_permissions_role_permission", columnNames = ["role_id", "permission_id"])],
    indexes = [Index(name = "idx_role_permissions_role", columnList = "role_id"), Index(name = "idx_role_permissions_permission", columnList = "permission_id")]
)
class RolePermissionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    var role: RoleEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    var permission: PermissionEntity? = null
)

@Entity
@Table(
    name = "auth_refresh_tokens",
    indexes = [Index(name = "idx_auth_refresh_tokens_user", columnList = "user_id")]
)
class RefreshTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUserEntity? = null,
    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    var tokenHash: String = "",
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(nullable = false)
    var revoked: Boolean = false,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null
)
