package com.chico.dbinspector.auth

import jakarta.transaction.Transactional
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AdminUserService(
    private val userRepository: AppUserRepository,
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val auditService: AdminAuditService,
    private val passwordEncoder: PasswordEncoder
) {
    companion object {
        private val protectedRoles = setOf("ADMIN", "USER")
    }

    fun listUsers(): List<AdminUserResponse> =
        userRepository.findAll()
            .sortedBy { it.email.lowercase() }
            .map { toAdminUserResponse(it) }

    @Transactional
    fun createUser(request: AdminCreateUserRequest): AdminUserResponse {
        val name = request.name.trim()
        require(name.isNotBlank()) { "Nome obrigatorio" }
        val email = request.email.trim().lowercase()
        require(email.isNotBlank()) { "Email obrigatorio" }
        val password = request.password.trim()
        require(password.length >= 6) { "Senha deve ter pelo menos 6 caracteres" }

        if (userRepository.findByEmailIgnoreCase(email).isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ja existe usuario com esse email")
        }

        val user = userRepository.save(
            AppUserEntity(
                name = name,
                email = email,
                passwordHash = passwordEncoder.encode(password),
                active = request.active
            )
        )

        val roleNames = request.roles
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("USER") }

        assignRoles(user, roleNames)
        auditService.log(
            action = "ADMIN_USER_CREATE",
            targetType = "USER",
            targetId = user.id?.toString(),
            details = mapOf("email" to user.email, "name" to user.name, "roles" to roleNames)
        )
        return toAdminUserResponse(user)
    }

    @Transactional
    fun setUserActive(id: UUID, active: Boolean): AdminUserResponse {
        val user = findUser(id)
        user.active = active
        if (!active) {
            refreshTokenRepository.findAllByUserIdAndRevokedFalse(id).forEach {
                it.revoked = true
                refreshTokenRepository.save(it)
            }
        }
        val saved = userRepository.save(user)
        auditService.log(
            action = "ADMIN_USER_SET_ACTIVE",
            targetType = "USER",
            targetId = saved.id?.toString(),
            details = mapOf("active" to active)
        )
        return toAdminUserResponse(saved)
    }

    @Transactional
    fun setUserName(id: UUID, rawName: String): AdminUserResponse {
        val user = findUser(id)
        val name = rawName.trim()
        require(name.isNotBlank()) { "Nome obrigatorio" }
        user.name = name
        val saved = userRepository.save(user)
        auditService.log(
            action = "ADMIN_USER_SET_NAME",
            targetType = "USER",
            targetId = saved.id?.toString(),
            details = mapOf("name" to name)
        )
        return toAdminUserResponse(saved)
    }

    @Transactional
    fun resetPassword(id: UUID, rawPassword: String) {
        val user = findUser(id)
        val password = rawPassword.trim()
        require(password.length >= 6) { "Senha deve ter pelo menos 6 caracteres" }

        user.passwordHash = passwordEncoder.encode(password)
        userRepository.save(user)

        refreshTokenRepository.findAllByUserIdAndRevokedFalse(id).forEach {
            it.revoked = true
            refreshTokenRepository.save(it)
        }
        auditService.log(
            action = "ADMIN_USER_RESET_PASSWORD",
            targetType = "USER",
            targetId = id.toString()
        )
    }

    @Transactional
    fun assignRole(userId: UUID, roleName: String): AdminUserResponse {
        val user = findUser(userId)
        val normalizedRole = roleName.trim().uppercase()
        require(normalizedRole.isNotBlank()) { "Role obrigatoria" }
        assignRoles(user, listOf(normalizedRole))
        auditService.log(
            action = "ADMIN_USER_ASSIGN_ROLE",
            targetType = "USER",
            targetId = user.id?.toString(),
            details = mapOf("role" to normalizedRole)
        )
        return toAdminUserResponse(user)
    }

    @Transactional
    fun removeRole(userId: UUID, roleName: String): AdminUserResponse {
        val user = findUser(userId)
        val normalizedRole = roleName.trim().uppercase()
        val link = userRoleRepository.findByUserIdAndRoleNameIgnoreCase(userId, normalizedRole).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Role nao vinculada ao usuario")
        }
        userRoleRepository.delete(link)
        auditService.log(
            action = "ADMIN_USER_REMOVE_ROLE",
            targetType = "USER",
            targetId = user.id?.toString(),
            details = mapOf("role" to normalizedRole)
        )
        return toAdminUserResponse(user)
    }

    @Transactional
    fun revokeRefreshTokens(userId: UUID) {
        findUser(userId)
        refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId).forEach {
            it.revoked = true
            refreshTokenRepository.save(it)
        }
        auditService.log(
            action = "ADMIN_USER_REVOKE_TOKENS",
            targetType = "USER",
            targetId = userId.toString()
        )
    }

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

    private fun assignRoles(user: AppUserEntity, roleNames: List<String>) {
        val userId = user.id ?: error("Usuario sem id")
        roleNames.forEach { roleName ->
            val role = roleRepository.findByNameIgnoreCase(roleName).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Role '$roleName' nao existe")
            }
            val roleId = role.id ?: error("Role sem id")
            if (!userRoleRepository.existsByUserIdAndRoleId(userId, roleId)) {
                userRoleRepository.save(UserRoleEntity(user = user, role = role))
            }
        }
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

    private fun findUser(id: UUID): AppUserEntity =
        userRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario nao encontrado")
        }

    private fun toAdminUserResponse(user: AppUserEntity): AdminUserResponse {
        val userId = user.id ?: error("Usuario sem id")
        val created = user.createdAt ?: error("createdAt ausente")
        val updated = user.updatedAt ?: error("updatedAt ausente")

        val roles = userRoleRepository.findRoleNamesByUserId(userId)
            .map { it.uppercase() }
            .distinct()
            .sorted()

        return AdminUserResponse(
            id = userId.toString(),
            name = user.name?.trim().takeUnless { it.isNullOrBlank() } ?: "",
            email = user.email,
            active = user.active,
            roles = roles,
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }
}
