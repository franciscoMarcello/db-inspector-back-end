package com.chico.dbinspector.auth

import jakarta.transaction.Transactional
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AdminRoleManagementService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val auditService: AdminAuditService
) {
    private val protectedRoles = setOf("ADMIN")
    private val permissionLockedRoles = setOf("ADMIN")

    fun listRoles(): List<AdminRoleResponse> =
        roleRepository.findAllByOrderByNameAsc().map { role ->
            toAdminRoleResponse(role)
        }

    fun getRole(roleName: String): AdminRoleResponse {
        val role = findRoleByName(roleName)
        return toAdminRoleResponse(role)
    }

    @Transactional
    fun createRole(request: AdminCreateRoleRequest): AdminRoleResponse {
        val roleName = normalizeRoleName(request.name)
        if (roleRepository.findByNameIgnoreCase(roleName).isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Role '$roleName' ja existe")
        }

        val role = roleRepository.save(RoleEntity(name = roleName))
        val roleId = role.id ?: error("Role sem id")
        syncRolePermissions(roleId, normalizePermissionCodes(request.permissions))
        auditService.log(
            action = "ADMIN_ROLE_CREATE",
            targetType = "ROLE",
            targetId = roleName,
            details = mapOf("permissions" to request.permissions)
        )
        return toAdminRoleResponse(role)
    }

    @Transactional
    fun updateRole(currentRoleName: String, request: AdminUpdateRoleRequest): AdminRoleResponse {
        val role = findRoleByName(currentRoleName)
        val roleId = role.id ?: error("Role sem id")
        val currentName = role.name.uppercase()

        request.name?.trim()?.takeIf { it.isNotBlank() }?.let { newNameRaw ->
            val newName = normalizeRoleName(newNameRaw)
            if (protectedRoles.contains(currentName) && newName != currentName) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido renomear role de sistema")
            }
            if (!currentName.equals(newName, ignoreCase = true) && roleRepository.findByNameIgnoreCase(newName).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Role '$newName' ja existe")
            }
            role.name = newName
            roleRepository.save(role)
        }

        request.permissions?.let { permissionCodes ->
            val normalized = normalizePermissionCodes(permissionCodes)
            if (permissionLockedRoles.contains(currentName)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido alterar permissoes da role ADMIN")
            }
            if (protectedRoles.contains(currentName) && normalized.isEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Role de sistema nao pode ficar sem permissao")
            }
            syncRolePermissions(roleId, normalized)
        }
        auditService.log(
            action = "ADMIN_ROLE_UPDATE",
            targetType = "ROLE",
            targetId = role.id?.toString(),
            details = mapOf("name" to role.name, "permissions" to (request.permissions ?: emptyList<String>()))
        )

        return toAdminRoleResponse(role)
    }

    @Transactional
    fun deleteRole(roleName: String) {
        val role = findRoleByName(roleName)
        val normalizedName = role.name.uppercase()
        if (protectedRoles.contains(normalizedName)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido excluir role de sistema")
        }

        val roleId = role.id ?: error("Role sem id")
        if (userRoleRepository.existsByRoleId(roleId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Role vinculada a usuarios")
        }

        rolePermissionRepository.findAllByRoleId(roleId).forEach { rolePermissionRepository.delete(it) }
        roleRepository.delete(role)
        auditService.log(
            action = "ADMIN_ROLE_DELETE",
            targetType = "ROLE",
            targetId = role.name
        )
    }

    private fun syncRolePermissions(roleId: UUID, desiredPermissionCodes: Set<String>) {
        val currentLinks = rolePermissionRepository.findAllByRoleId(roleId)
        val currentCodesByLink = currentLinks.associateBy {
            it.permission?.code?.uppercase() ?: error("Permissao da role sem codigo")
        }

        val codesToRemove = currentCodesByLink.keys - desiredPermissionCodes
        codesToRemove.forEach { code ->
            currentCodesByLink[code]?.let { rolePermissionRepository.delete(it) }
        }

        val codesToAdd = desiredPermissionCodes - currentCodesByLink.keys
        val role = roleRepository.findById(roleId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Role nao encontrada")
        }
        codesToAdd.forEach { code ->
            val permission = permissionRepository.findByCode(code).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Permissao '$code' nao existe")
            }
            rolePermissionRepository.save(RolePermissionEntity(role = role, permission = permission))
        }
    }

    private fun toAdminRoleResponse(role: RoleEntity): AdminRoleResponse {
        val roleId = role.id ?: error("Role sem id")
        return AdminRoleResponse(
            name = role.name,
            permissions = rolePermissionRepository.findPermissionCodesByRoleIds(listOf(roleId)).sorted()
        )
    }

    private fun findRoleByName(roleName: String): RoleEntity {
        val normalizedName = normalizeRoleName(roleName)
        return roleRepository.findByNameIgnoreCase(normalizedName).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Role '$normalizedName' nao encontrada")
        }
    }

    private fun normalizeRoleName(roleName: String): String {
        val normalized = roleName.trim().uppercase()
        require(normalized.isNotBlank()) { "Role obrigatoria" }
        return normalized
    }

    private fun normalizePermissionCodes(permissionCodes: List<String>): Set<String> {
        val normalized = permissionCodes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val invalid = normalized - PermissionCodes.all.toSet()
        if (invalid.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Permissoes invalidas: ${invalid.sorted().joinToString(", ")}")
        }
        return normalized
    }
}
