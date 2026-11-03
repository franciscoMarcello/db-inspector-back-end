package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import io.jsonwebtoken.Claims
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Token valido, mas a pessoa nao pode entrar (sem e-mail verificado, desativada, e-mail de outra conta). */
class KeycloakLoginRejectedException(message: String) : RuntimeException(message)

/**
 * Transforma o token do Keycloak no [AuthUserPrincipal] que o resto do sistema ja usa (ACL, IA, versoes).
 *
 * A tabela users vira espelho de quem entrou pelo Keycloak: o id local continua sendo o que ACLs, tokens
 * de IA e autoria apontam. Quem ja existia e vinculado pelo e-mail, e so com e-mail verificado no
 * Keycloak: sem isso, uma conta com o e-mail de outra pessoa herdaria os acessos dela.
 *
 * Os perfis vem do token (roles do client de login); user_roles guarda a copia do ultimo acesso, para a
 * tela de usuarios. As permissoes de cada perfil continuam no banco.
 */
@Service
class KeycloakUserService(
    private val properties: DbInspectorProperties,
    private val userRepository: AppUserRepository,
    private val roleRepository: RoleRepository,
    private val userRoleRepository: UserRoleRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val auditService: AdminAuditService
) {
    private val log = LoggerFactory.getLogger(KeycloakUserService::class.java)

    @Transactional
    fun principalFor(claims: Claims): AuthUserPrincipal {
        val keycloakId = claims.subject?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw KeycloakLoginRejectedException("Token do Keycloak sem identificacao do usuario")
        val email = claims["email"]?.toString()?.trim()?.lowercase().takeUnless { it.isNullOrBlank() }
        val emailVerified = claims["email_verified"] == true
        val name = listOf("name", "preferred_username")
            .firstNotNullOfOrNull { claim -> claims[claim]?.toString()?.trim()?.takeIf { it.isNotBlank() } }

        val user = userRepository.findByKeycloakId(keycloakId).orElse(null)
            ?: linkOrCreate(keycloakId, email, emailVerified, name)
        if (!user.active) {
            throw KeycloakLoginRejectedException("Usuario desativado no agroreports. Fale com um administrador")
        }
        updateProfile(user, email, emailVerified, name)

        val roles = syncRoles(user, tokenRoles(claims))
        return AuthUserPrincipal(
            userId = user.id ?: error("Usuario sem id"),
            name = user.name?.trim().takeUnless { it.isNullOrBlank() } ?: "",
            email = user.email,
            passwordHash = "",
            active = true,
            authoritiesList = authoritiesFor(roles)
        )
    }

    /** ROLE_* e permissoes dos perfis informados; nome que nao e perfil do agroreports e ignorado. */
    fun authoritiesForRoleNames(roleNames: Collection<String>): List<String> = authoritiesFor(knownRoles(roleNames))

    private fun linkOrCreate(keycloakId: String, email: String?, emailVerified: Boolean, name: String?): AppUserEntity {
        if (email == null || !emailVerified) {
            throw KeycloakLoginRejectedException(
                "O Keycloak nao confirmou o e-mail desta conta. Peca ao administrador do Keycloak para marcar o e-mail como verificado"
            )
        }
        val existing = userRepository.findByEmailIgnoreCase(email).orElse(null)
        if (existing != null) {
            if (existing.keycloakId != null) {
                throw KeycloakLoginRejectedException("Este e-mail ja esta vinculado a outra conta do Keycloak")
            }
            existing.keycloakId = keycloakId
            return userRepository.save(existing).also { audit("AUTH_SSO_LINK", it) }
        }
        return userRepository.save(
            AppUserEntity(name = name ?: email, email = email, keycloakId = keycloakId, active = true)
        ).also { audit("AUTH_SSO_PROVISION", it) }
    }

    private fun updateProfile(user: AppUserEntity, email: String?, emailVerified: Boolean, name: String?) {
        var changed = false
        if (name != null && name != user.name) {
            user.name = name
            changed = true
        }
        if (email != null && emailVerified && !email.equals(user.email, ignoreCase = true)) {
            if (userRepository.findByEmailIgnoreCase(email).isEmpty) {
                user.email = email
                changed = true
            } else {
                log.warn("E-mail do Keycloak do usuario {} ja pertence a outro usuario local; mantido o atual", user.id)
            }
        }
        if (changed) userRepository.save(user)
    }

    private fun tokenRoles(claims: Claims): Set<String> {
        val client = (claims["resource_access"] as? Map<*, *>)?.get(properties.keycloak.clientId) as? Map<*, *>
        return (client?.get("roles") as? List<*>).orEmpty()
            .mapNotNull { role -> role?.toString()?.trim()?.uppercase()?.takeIf { it.isNotBlank() } }
            .toSet()
    }

    private fun knownRoles(roleNames: Collection<String>): List<RoleEntity> {
        val normalized = roleNames.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        return if (normalized.isEmpty()) emptyList() else roleRepository.findAllByNameIn(normalized)
    }

    private fun syncRoles(user: AppUserEntity, roleNames: Set<String>): List<RoleEntity> {
        val userId = user.id ?: error("Usuario sem id")
        val roles = knownRoles(roleNames)
        val wanted = roles.mapNotNull { it.id }.toSet()
        val current = userRoleRepository.findAllByUserId(userId)
        current.filter { it.role?.id !in wanted }.forEach { userRoleRepository.delete(it) }
        val kept = current.mapNotNull { it.role?.id }.toSet()
        roles.filter { it.id !in kept }.forEach { userRoleRepository.save(UserRoleEntity(user = user, role = it)) }
        return roles
    }

    private fun authoritiesFor(roles: List<RoleEntity>): List<String> {
        val roleIds = roles.mapNotNull { it.id }
        val permissions = if (roleIds.isEmpty()) emptyList() else rolePermissionRepository.findPermissionCodesByRoleIds(roleIds)
        return roles.map { "ROLE_${it.name.uppercase()}" } + permissions
    }

    private fun audit(action: String, user: AppUserEntity) {
        auditService.log(
            action = action,
            targetType = "USER",
            targetId = user.id?.toString(),
            details = mapOf("email" to user.email, "keycloakId" to user.keycloakId)
        )
    }
}
