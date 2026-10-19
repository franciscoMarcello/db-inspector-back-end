package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Liberacao pelo operador: a IA pede para passar dos limites numa consulta, uma pessoa aprova ou
 * nega na tela. A aprovacao vale para aquele token e aquele SQL, ate expirar.
 */
@Service
class AiApprovalService(
    private val repository: AiQueryApprovalRepository,
    private val guard: AiSqlGuard,
    private val auditService: AdminAuditService,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional
    fun request(body: AiApprovalRequest, estimatedCost: Double? = null): AiApprovalResponse {
        val token = currentAiToken() ?: throw AiRuleException(
            HttpStatus.FORBIDDEN, "somente_token_de_ia", "Liberacao e pedida por token de IA",
            "Pessoas executam pela tela, sem liberacao."
        )
        val sql = normalize(body.sql)
        // Nao adianta pedir liberacao para o que nunca sera liberado.
        guard.requireStaticOk(sql)
        val rows = (body.linhas ?: properties.ai.approvalMaxRows).coerceIn(1, properties.ai.approvalMaxRows)
        val hash = hash(sql)

        repository.findAllByTokenIdAndSqlHashOrderByCreatedAtDesc(token.tokenId, hash)
            .firstOrNull { it.status == AiApprovalStatus.PENDING.name }
            ?.let { return it.toResponse() }

        val saved = repository.save(
            AiQueryApprovalEntity(
                tokenId = token.tokenId,
                tokenName = token.tokenName,
                requestedBy = currentEmail(),
                sqlHash = hash,
                sql = sql,
                reason = body.motivo.trim(),
                requestedRows = rows,
                estimatedCost = estimatedCost
            )
        )
        return saved.toResponse()
    }

    @Transactional(readOnly = true)
    fun getForToken(id: UUID): AiApprovalResponse {
        val entity = find(id)
        val token = currentAiToken()
        if (token != null && token.tokenId != entity.tokenId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Liberacao nao encontrada")
        }
        return entity.toResponse()
    }

    @Transactional(readOnly = true)
    fun list(status: String?): List<AiApprovalResponse> {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val items = if (normalized == null) {
            repository.findTop100ByOrderByCreatedAtDesc()
        } else {
            require(normalized in AiApprovalStatus.entries.map { it.name }) { "Status invalido: $status" }
            repository.findAllByStatusOrderByCreatedAtDesc(normalized)
        }
        return items.map { it.toResponse() }
    }

    fun pendingCount(): Long = repository.countByStatus(AiApprovalStatus.PENDING.name)

    @Transactional
    fun approve(id: UUID, body: AiApprovalDecisionRequest): AiApprovalResponse {
        val entity = findPending(id)
        val rows = (body.linhas ?: entity.requestedRows).coerceIn(1, properties.ai.approvalMaxRows)
        val minutes = (body.minutos ?: properties.ai.approvalTtlMinutes).coerceIn(1, properties.ai.approvalMaxTtlMinutes)
        val now = Instant.now(clock)
        entity.status = AiApprovalStatus.APPROVED.name
        entity.approvedRows = rows
        entity.expiresAt = now.plus(Duration.ofMinutes(minutes))
        entity.decidedAt = now
        entity.decidedBy = currentEmail()
        entity.decisionNote = body.nota?.trim().takeUnless { it.isNullOrBlank() }
        val saved = repository.save(entity)
        auditService.log(
            action = "AI_QUERY_APPROVE",
            targetType = "AI_QUERY_APPROVAL",
            targetId = id.toString(),
            details = mapOf("rows" to rows, "minutes" to minutes, "token" to entity.tokenName)
        )
        return saved.toResponse()
    }

    @Transactional
    fun deny(id: UUID, body: AiApprovalDecisionRequest): AiApprovalResponse {
        val entity = findPending(id)
        entity.status = AiApprovalStatus.DENIED.name
        entity.decidedAt = Instant.now(clock)
        entity.decidedBy = currentEmail()
        entity.decisionNote = body.nota?.trim().takeUnless { it.isNullOrBlank() }
        val saved = repository.save(entity)
        auditService.log(
            action = "AI_QUERY_DENY",
            targetType = "AI_QUERY_APPROVAL",
            targetId = id.toString(),
            details = mapOf("token" to entity.tokenName)
        )
        return saved.toResponse()
    }

    /** Aprovacao vigente deste token para este SQL, se houver. */
    @Transactional(readOnly = true)
    fun findActive(tokenId: UUID, sql: String): AiQueryApprovalEntity? {
        val now = Instant.now(clock)
        return repository.findAllByTokenIdAndSqlHashOrderByCreatedAtDesc(tokenId, hash(normalize(sql)))
            .firstOrNull { it.status == AiApprovalStatus.APPROVED.name && it.expiresAt?.isAfter(now) == true }
    }

    private fun findPending(id: UUID): AiQueryApprovalEntity {
        val entity = find(id)
        if (entity.status != AiApprovalStatus.PENDING.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Liberacao ja decidida")
        }
        return entity
    }

    private fun find(id: UUID): AiQueryApprovalEntity =
        repository.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Liberacao nao encontrada") }

    private fun AiQueryApprovalEntity.toResponse(): AiApprovalResponse {
        val now = Instant.now(clock)
        return AiApprovalResponse(
            id = id?.toString() ?: error("Liberacao sem id"),
            status = status,
            sql = sql,
            motivo = reason,
            linhasPedidas = requestedRows,
            linhasAprovadas = approvedRows,
            custoEstimado = estimatedCost,
            token = tokenName,
            solicitadoPor = requestedBy,
            decididoPor = decidedBy,
            decididoEm = decidedAt?.toEpochMilli(),
            nota = decisionNote,
            expiraEm = expiresAt?.toEpochMilli(),
            criadoEm = (createdAt ?: now).toEpochMilli(),
            vigente = status == AiApprovalStatus.APPROVED.name && expiresAt?.isAfter(now) == true
        )
    }

    private fun currentEmail(): String =
        (SecurityContextHolder.getContext().authentication?.principal as? AuthUserPrincipal)?.username ?: "desconhecido"

    private fun normalize(sql: String): String = sql.trim().removeSuffix(";").trim()

    /** Hash do SQL exato (so as bordas sao aparadas): espaco dentro de texto muda o significado. */
    private fun hash(sql: String): String =
        MessageDigest.getInstance("SHA-256").digest(sql.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
