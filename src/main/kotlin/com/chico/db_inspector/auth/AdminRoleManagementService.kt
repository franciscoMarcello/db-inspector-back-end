package com.chico.dbinspector.auth

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AdminRoleManagementService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val auditService: AdminAuditService,
    /** So existe com dbinspector.keycloak.admin.enabled=true: perfis viram roles do client de login. */
    private val keycloakAdmin: KeycloakAdminClient? = null,
    private val transactionManager: PlatformTransactionManager? = null
) {
    private val log = LoggerFactory.getLogger(AdminRoleManagementService::class.java)
    private val protectedRoles = setOf("ADMIN")
    private val permissionLockedRoles = setOf("ADMIN")

    fun listRoles(): List<AdminRoleResponse> {
        val keycloakStatus = keycloakStatusLookup()
        return roleRepository.findAllByOrderByNameAsc().map { role ->
            toAdminRoleResponse(role, keycloakStatus?.invoke(role.name))
        }
    }

    fun getRole(roleName: String): AdminRoleResponse {
        val role = findRoleByName(roleName)
        return toAdminRoleResponse(role, keycloakStatusLookup()?.invoke(role.name))
    }

    @Transactional
    fun createRole(request: AdminCreateRoleRequest): AdminRoleResponse {
        val roleName = normalizeRoleName(request.name)
        requireKeycloakRoleName(roleName)
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
        val ensured = keycloak("criado") { it.ensureRole(roleName) }
        if (ensured == RoleEnsureResult.CREATED) {
            compensateOnRollback(
                "criacao do perfil $roleName",
                stillNeeded = { roleRepository.findByNameIgnoreCase(roleName).isEmpty }
            ) { it.removeRole(roleName) }
        }
        return toAdminRoleResponse(role, ensured?.toStatus())
    }

    @Transactional
    fun updateRole(currentRoleName: String, request: AdminUpdateRoleRequest): AdminRoleResponse {
        val role = findRoleByName(currentRoleName)
        val roleId = role.id ?: error("Role sem id")
        val currentName = role.name.uppercase()

        val newName = request.name?.trim()?.takeIf { it.isNotBlank() }?.let { newNameRaw ->
            val newName = normalizeRoleName(newNameRaw)
            if (protectedRoles.contains(currentName) && newName != currentName) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido renomear role de sistema")
            }
            if (!currentName.equals(newName, ignoreCase = true) && roleRepository.findByNameIgnoreCase(newName).isPresent) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Role '$newName' ja existe")
            }
            // Perfil antigo com nome fora do padrao continua editavel; so o nome novo precisa servir de role.
            if (newName != currentName) requireKeycloakRoleName(newName)
            role.name = newName
            roleRepository.save(role)
            newName
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
        val keycloakStatus = if (newName != null && newName != currentName) {
            keycloak("renomeado") { it.renameRole(currentName, newName) }
            compensateOnRollback(
                "renomeacao do perfil $currentName para $newName",
                stillNeeded = {
                    roleRepository.findByNameIgnoreCase(currentName).isPresent &&
                        roleRepository.findByNameIgnoreCase(newName).isEmpty
                }
            ) { it.renameRole(newName, currentName) }
            keycloakAdmin?.let { KeycloakRoleStatus.SYNCED }
        } else {
            ensureExistingRole(role.name)
        }

        return toAdminRoleResponse(role, keycloakStatus)
    }

    @Transactional
    fun deleteRole(roleName: String) {
        val role = findRoleByName(roleName)
        val normalizedName = role.name.uppercase()
        if (protectedRoles.contains(normalizedName)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido excluir role de sistema")
        }

        val roleId = role.id ?: error("Role sem id")
        if (keycloakAdmin == null) {
            if (userRoleRepository.existsByRoleId(roleId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Role vinculada a usuarios")
            }
        } else {
            // Para quem entra pelo Keycloak, user_roles e so a copia do ultimo acesso e pode estar velha:
            // quem diz se alguem ainda tem o perfil e o Keycloak.
            val links = userRoleRepository.findAllByRoleId(roleId)
            if (links.any { it.user?.keycloakId == null }) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Role vinculada a usuarios")
            }
            if (keycloak("excluido") { it.roleInUse(normalizedName) } == true) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Perfil atribuido a pessoas no Keycloak: retire a atribuicao no console do Keycloak antes de excluir"
                )
            }
            links.forEach { userRoleRepository.delete(it) }
        }

        rolePermissionRepository.findAllByRoleId(roleId).forEach { rolePermissionRepository.delete(it) }
        roleRepository.delete(role)
        auditService.log(
            action = "ADMIN_ROLE_DELETE",
            targetType = "ROLE",
            targetId = role.name
        )
        if (keycloak("excluido") { it.removeRole(normalizedName) } == true) {
            compensateOnRollback(
                "exclusao do perfil $normalizedName",
                stillNeeded = { roleRepository.findByNameIgnoreCase(normalizedName).isPresent }
            ) { it.ensureRole(normalizedName) }
        }
    }

    /**
     * A chamada ao Keycloak vai por ultimo, mas o commit ainda pode falhar depois dela. O motivo mais provavel
     * e uma operacao paralela com o mesmo nome que gravou antes: por isso so desfaz no Keycloak se o banco,
     * relido numa transacao nova, ainda pedir isso ([stillNeeded]). Falha da compensacao fica no log; a
     * conferencia do boot recria as roles que faltarem.
     *
     * Limites conhecidos: (1) uma criacao paralela que grave entre a releitura e a compensacao fica com
     * perfil sem role ate o proximo boot (uma trava no banco pelo nome do perfil fecharia essa corrida entre
     * operacoes do app; nao foi adotada por ser desproporcional ao risco); (2) uma chamada que o Keycloak
     * conclua depois do timeout pode escapar da releitura. Repetir a operacao costuma resolver; num renomear
     * em que o boot rode antes da nova tentativa, confira a role antiga e a nova no console.
     */
    private fun compensateOnRollback(
        description: String,
        stillNeeded: () -> Boolean,
        undo: (KeycloakAdminClient) -> Unit
    ) {
        val client = keycloakAdmin ?: return
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_UNKNOWN) {
                    log.error("Resultado do commit desconhecido apos a {}: confira o perfil e a role no Keycloak", description)
                    return
                }
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return
                try {
                    if (!readInNewTransaction(stillNeeded)) {
                        log.info("Transacao da {} desfeita, mas o banco ja esta coerente com o Keycloak", description)
                        return
                    }
                    undo(client)
                    log.warn("Transacao desfeita depois do Keycloak aceitar a {}: desfeito tambem no Keycloak", description)
                } catch (e: Exception) {
                    log.error("Perfil e role do Keycloak ficaram diferentes apos falha na {}: {}", description, e.message)
                }
            }
        })
    }

    /** Depois do fim da transacao do perfil, uma leitura so enxerga o que foi gravado se abrir outra. */
    private fun readInNewTransaction(block: () -> Boolean): Boolean {
        val manager = transactionManager ?: return block()
        val template = TransactionTemplate(manager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isReadOnly = true
        }
        return template.execute { block() } == true
    }

    /**
     * Chamada ao Keycloak dentro da transacao do perfil: se falhar, a mudanca local e desfeita. A chamada vai
     * por ultimo para que, em geral, so o banco precise ser desfeito; os casos em que o Keycloak ja mudou
     * estao em [compensateOnRollback].
     */
    private fun <T> keycloak(action: String, block: (KeycloakAdminClient) -> T): T? {
        val client = keycloakAdmin ?: return null
        return try {
            block(client)
        } catch (e: KeycloakAdminException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Perfil nao foi $action: ${e.message}")
        }
    }

    private fun requireKeycloakRoleName(name: String) {
        if (keycloakAdmin != null && !KeycloakAdminClient.isValidRoleName(name)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Nome de perfil invalido: use letras sem acento, numeros, _ ou -, sem espacos (ate 64)"
            )
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

    private fun toAdminRoleResponse(role: RoleEntity, keycloakStatus: KeycloakRoleStatus? = null): AdminRoleResponse {
        val roleId = role.id ?: error("Role sem id")
        return AdminRoleResponse(
            name = role.name,
            permissions = rolePermissionRepository.findPermissionCodesByRoleIds(listOf(roleId)).sorted(),
            keycloak = keycloakStatus
        )
    }

    /**
     * Situacao de cada perfil no Keycloak com uma consulta so para a lista inteira. null = sincronizacao
     * desligada. Keycloak fora do ar nao impede a tela de abrir: os perfis aparecem como indisponiveis.
     */
    private fun keycloakStatusLookup(): ((String) -> KeycloakRoleStatus)? {
        val client = keycloakAdmin ?: return null
        val roles = try {
            client.listRoles()
        } catch (e: KeycloakAdminException) {
            log.warn("Nao foi possivel ler as roles do Keycloak para a tela de perfis: {}", e.message)
            return { KeycloakRoleStatus.UNAVAILABLE }
        }
        return { name ->
            when (roles[name.uppercase()]) {
                true -> KeycloakRoleStatus.SYNCED
                false -> KeycloakRoleStatus.MANUAL
                null -> KeycloakRoleStatus.MISSING
            }
        }
    }

    /**
     * Salvar um perfil sem role no Keycloak (anterior a integracao, ou criado com o Keycloak fora do ar) cria
     * a role. A mudanca deste salvamento e so do banco (permissoes): Keycloak fora do ar nao a impede, o
     * perfil so aparece como indisponivel.
     */
    private fun ensureExistingRole(name: String): KeycloakRoleStatus? {
        val client = keycloakAdmin ?: return null
        if (!KeycloakAdminClient.isValidRoleName(name.uppercase())) return KeycloakRoleStatus.MISSING
        return try {
            client.ensureExistingRole(name).toStatus()
        } catch (e: KeycloakAdminException) {
            log.warn("Perfil '{}' salvo, mas o Keycloak nao respondeu para conferir a role: {}", name, e.message)
            KeycloakRoleStatus.UNAVAILABLE
        }
    }

    private fun RoleEnsureResult.toStatus(): KeycloakRoleStatus = when (this) {
        RoleEnsureResult.CREATED, RoleEnsureResult.EXISTING_MANAGED -> KeycloakRoleStatus.SYNCED
        RoleEnsureResult.EXISTING_UNMANAGED -> KeycloakRoleStatus.MANUAL
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
