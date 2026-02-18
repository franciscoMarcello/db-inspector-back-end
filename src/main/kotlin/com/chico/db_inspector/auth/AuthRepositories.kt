package com.chico.dbinspector.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUserEntity, UUID> {
    fun findByEmailIgnoreCase(email: String): Optional<AppUserEntity>
}

interface RoleRepository : JpaRepository<RoleEntity, UUID> {
    fun findByNameIgnoreCase(name: String): Optional<RoleEntity>
    fun findAllByOrderByNameAsc(): List<RoleEntity>
}

interface PermissionRepository : JpaRepository<PermissionEntity, UUID> {
    fun findByCode(code: String): Optional<PermissionEntity>
}

interface UserRoleRepository : JpaRepository<UserRoleEntity, UUID> {
    @Query(
        """
        select distinct ur.role.name
        from UserRoleEntity ur
        where ur.user.id = :userId
        """
    )
    fun findRoleNamesByUserId(userId: UUID): List<String>

    @Query(
        """
        select distinct ur.role.id
        from UserRoleEntity ur
        where ur.user.id = :userId
        """
    )
    fun findRoleIdsByUserId(userId: UUID): List<UUID>

    fun existsByUserIdAndRoleId(userId: UUID, roleId: UUID): Boolean
    fun existsByRoleId(roleId: UUID): Boolean
    fun findAllByUserId(userId: UUID): List<UserRoleEntity>
    fun findByUserIdAndRoleNameIgnoreCase(userId: UUID, roleName: String): Optional<UserRoleEntity>
}

interface RolePermissionRepository : JpaRepository<RolePermissionEntity, UUID> {
    @Query(
        """
        select distinct rp.permission.code
        from RolePermissionEntity rp
        where rp.role.id in :roleIds
        """
    )
    fun findPermissionCodesByRoleIds(roleIds: Collection<UUID>): List<String>

    fun existsByRoleIdAndPermissionId(roleId: UUID, permissionId: UUID): Boolean
    fun findAllByRoleId(roleId: UUID): List<RolePermissionEntity>
}

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {
    fun findByTokenHashAndRevokedFalse(tokenHash: String): Optional<RefreshTokenEntity>
    fun findAllByUserIdAndRevokedFalse(userId: UUID): List<RefreshTokenEntity>
}
