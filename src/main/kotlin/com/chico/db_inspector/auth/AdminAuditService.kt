package com.chico.dbinspector.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.util.UUID

interface AdminAuditLogRepository : JpaRepository<AdminAuditLogEntity, UUID>

@Service
class AdminAuditService(
    private val repository: AdminAuditLogRepository,
    private val objectMapper: ObjectMapper
) {
    fun log(action: String, targetType: String, targetId: String?, details: Map<String, Any?> = emptyMap()) {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal as? AuthUserPrincipal

        val entity = AdminAuditLogEntity(
            actorUserId = principal?.userId,
            actorEmail = principal?.username,
            action = action,
            targetType = targetType,
            targetId = targetId,
            details = objectMapper.writeValueAsString(details)
        )
        repository.save(entity)
    }
}
