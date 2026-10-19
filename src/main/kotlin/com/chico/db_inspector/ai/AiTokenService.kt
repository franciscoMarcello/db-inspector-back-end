package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AppUserDetailsService
import com.chico.dbinspector.auth.AppUserEntity
import com.chico.dbinspector.auth.AppUserRepository
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.auth.KeycloakAdminClient
import com.chico.dbinspector.auth.KeycloakAdminException
import com.chico.dbinspector.auth.KeycloakUserAccess
import com.chico.dbinspector.auth.KeycloakUserService
import com.chico.dbinspector.auth.PermissionCodes
import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.report.ReportVersionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tokens para agentes de IA (qualquer fornecedor) criarem relatorios pela API.
 *
 *  - o valor aparece uma unica vez, na criacao; o banco guarda so o SHA-256;
 *  - expiracao obrigatoria, com teto configuravel;
 *  - revogavel a qualquer momento, e deixa de valer se quem criou for desativado;
 *  - age com a identidade de quem criou (ACL inclusive), mas so com permissoes de relatorio e
 *    so dentro de /api/db/ai/... (o filtro de autenticacao garante a rota).
 */
@Service
class AiTokenService(
    private val repository: AiTokenRepository,
    private val userRepository: AppUserRepository,
    private val userDetailsService: AppUserDetailsService,
    private val auditService: AdminAuditService,
    private val properties: DbInspectorProperties,
    private val keycloakUserService: KeycloakUserService,
    private val keycloakAdmin: KeycloakAdminClient? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val TOKEN_PREFIX = "rpt_"

        /** Permissoes que um token pode ter, mesmo que o criador tenha mais. */
        val ALLOWED_PERMISSIONS = setOf(
            PermissionCodes.REPORT_READ,
            PermissionCodes.REPORT_WRITE,
            PermissionCodes.REPORT_RUN,
            PermissionCodes.FOLDER_READ,
            PermissionCodes.TEMPLATE_READ
        )

        private val LAST_USE_THROTTLE: Duration = Duration.ofMinutes(1)

        /** Quanto tempo um perfil retirado no Keycloak ainda vale num token de IA. */
        private val KEYCLOAK_ACCESS_TTL: Duration = Duration.ofMinutes(5)
    }

    private val log = LoggerFactory.getLogger(AiTokenService::class.java)
    private val random = SecureRandom()
    private val keycloakAccessCache = ConcurrentHashMap<String, CachedAccess>()

    @Transactional
    fun create(request: AiTokenCreateRequest): AiTokenCreatedResponse {
        val name = request.name.trim()
        require(name.isNotBlank()) { "Informe um nome para identificar o token" }
        val days = request.validityDays ?: properties.ai.tokenDefaultDays
        require(days in 1..properties.ai.tokenMaxDays) {
            "A validade deve estar entre 1 e ${properties.ai.tokenMaxDays} dias"
        }
        val creator = currentPrincipal()

        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val value = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val saved = repository.save(
            AiTokenEntity(
                name = name,
                tokenHash = hash(value),
                prefix = value.take(12),
                createdByUserId = creator.userId,
                createdByEmail = creator.username,
                expiresAt = Instant.now(clock).plus(Duration.ofDays(days.toLong()))
            )
        )
        val id = saved.id ?: error("Token sem id")
        auditService.log(
            action = "AI_TOKEN_CREATE",
            targetType = "AI_TOKEN",
            targetId = id.toString(),
            details = mapOf("name" to name, "validityDays" to days)
        )
        return AiTokenCreatedResponse(
            id = id.toString(),
            name = name,
            token = value,
            expiresAt = saved.expiresAt.toEpochMilli(),
            warning = "Guarde agora: este valor nao sera exibido de novo."
        )
    }

    @Transactional(readOnly = true)
    fun list(): List<AiTokenResponse> {
        val now = Instant.now(clock)
        return repository.findAllByOrderByCreatedAtDesc().map { it.toResponse(now) }
    }

    @Transactional
    fun revoke(id: UUID) {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Token nao encontrado")
        }
        if (entity.revokedAt != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Token ja revogado")
        }
        entity.revokedAt = Instant.now(clock)
        entity.revokedBy = currentPrincipal().username
        repository.save(entity)
        auditService.log(action = "AI_TOKEN_REVOKE", targetType = "AI_TOKEN", targetId = id.toString())
    }

    /**
     * Valida o token e devolve a identidade com que ele age, ou null. A busca e pelo hash: o valor
     * apresentado nunca e comparado em texto.
     */
    @Transactional
    fun authenticate(value: String): AiTokenIdentity? {
        if (!value.startsWith(TOKEN_PREFIX)) return null
        val token = repository.findByTokenHash(hash(value)) ?: return null
        val now = Instant.now(clock)
        if (token.revokedAt != null || !token.expiresAt.isAfter(now)) return null

        val creatorId = token.createdByUserId ?: return null
        val creator = userRepository.findById(creatorId).orElse(null) ?: return null
        if (!creator.active) return null
        val creatorAuthorities = creatorAuthorities(creator) ?: return null
        val isAdmin = creatorAuthorities.any { it.equals("ROLE_ADMIN", ignoreCase = true) }
        val roles = creatorAuthorities.filter { it.startsWith("ROLE_") }
        // Admin tem todas as permissoes no catalogo; as dos demais vem dos papeis.
        val permissions = if (isAdmin) ALLOWED_PERMISSIONS else creatorAuthorities.filter { it in ALLOWED_PERMISSIONS }.toSet()

        if (token.lastUsedAt?.isBefore(now.minus(LAST_USE_THROTTLE)) != false) {
            token.lastUsedAt = now
            repository.save(token)
        }

        val principal = AuthUserPrincipal(
            userId = creatorId,
            name = "IA: ${token.name}",
            email = creator.email,
            passwordHash = "",
            active = true,
            authoritiesList = roles + permissions + ReportVersionService.AI_AGENT_AUTHORITY
        )
        return AiTokenIdentity(principal, token.id ?: return null, token.name)
    }

    /**
     * Perfis de quem criou o token, agora. Quem entra pelo Keycloak e consultado la: o token de IA nao traz
     * JWT de onde ler os perfis, e a copia do ultimo acesso pode ter meses. Keycloak fora do ar nega o token.
     */
    private fun creatorAuthorities(creator: AppUserEntity): List<String>? {
        val keycloakId = creator.keycloakId
        if (keycloakId != null) {
            // Sem o Keycloak para consultar, a unica fonte seria a copia local: nega.
            if (!properties.keycloak.enabled) return null
            val access = keycloakAccess(keycloakId) ?: return null
            if (!access.enabled) return null
            return keycloakUserService.authoritiesForRoleNames(access.roles)
        }
        if (!properties.localLoginEnabled()) return null
        val details = runCatching { userDetailsService.loadUserByUsername(creator.email) }
            .getOrNull() as? AuthUserPrincipal ?: return null
        return details.authorities.map { it.authority }
    }

    private fun keycloakAccess(keycloakId: String): KeycloakUserAccess? {
        val client = keycloakAdmin
        if (client == null) {
            log.warn("Token de IA recusado: consultar perfis no Keycloak exige dbinspector.keycloak.admin.enabled=true")
            return null
        }
        val now = Instant.now(clock)
        keycloakAccessCache[keycloakId]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.access }
        val access = try {
            client.userAccess(keycloakId)
        } catch (e: KeycloakAdminException) {
            log.warn("Token de IA recusado: nao foi possivel consultar o Keycloak ({})", e.message)
            return null
        } ?: return null
        keycloakAccessCache[keycloakId] = CachedAccess(access, now.plus(KEYCLOAK_ACCESS_TTL))
        return access
    }

    private data class CachedAccess(val access: KeycloakUserAccess, val expiresAt: Instant)

    private fun AiTokenEntity.toResponse(now: Instant) = AiTokenResponse(
        id = id?.toString() ?: error("Token sem id"),
        name = name,
        prefix = prefix,
        createdBy = createdByEmail,
        createdAt = (createdAt ?: now).toEpochMilli(),
        expiresAt = expiresAt.toEpochMilli(),
        revokedAt = revokedAt?.toEpochMilli(),
        lastUsedAt = lastUsedAt?.toEpochMilli(),
        active = revokedAt == null && expiresAt.isAfter(now),
        expired = !expiresAt.isAfter(now)
    )

    private fun currentPrincipal(): AuthUserPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthUserPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
