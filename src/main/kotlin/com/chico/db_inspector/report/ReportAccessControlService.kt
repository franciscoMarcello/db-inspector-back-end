package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import java.util.UUID

@Service
class ReportAccessControlService(
    private val folderAclRepository: ReportFolderAclRepository,
    private val reportAclRepository: ReportAclRepository,
    private val properties: DbInspectorProperties
) {
    private val log = LoggerFactory.getLogger(ReportAccessControlService::class.java)

    fun canViewFolder(folderId: UUID): Boolean = canAccessFolder(folderId, AccessAction.VIEW)
    fun canRunFolder(folderId: UUID): Boolean = canAccessFolder(folderId, AccessAction.RUN)
    fun canEditFolder(folderId: UUID): Boolean = canAccessFolder(folderId, AccessAction.EDIT)
    fun canDeleteFolder(folderId: UUID): Boolean = canAccessFolder(folderId, AccessAction.DELETE)

    fun canViewReport(report: ReportEntity): Boolean = canAccessReport(report, AccessAction.VIEW)
    fun canRunReport(report: ReportEntity): Boolean = canAccessReport(report, AccessAction.RUN)
    fun canEditReport(report: ReportEntity): Boolean = canAccessReport(report, AccessAction.EDIT)
    fun canDeleteReport(report: ReportEntity): Boolean = canAccessReport(report, AccessAction.DELETE)

    fun requireFolderAccess(folderId: UUID, action: AccessAction) {
        val allowed = when (action) {
            AccessAction.VIEW -> canViewFolder(folderId)
            AccessAction.RUN -> canRunFolder(folderId)
            AccessAction.EDIT -> canEditFolder(folderId)
            AccessAction.DELETE -> canDeleteFolder(folderId)
        }
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissao para acessar a pasta")
    }

    fun requireReportAccess(report: ReportEntity, action: AccessAction) {
        val allowed = when (action) {
            AccessAction.VIEW -> canViewReport(report)
            AccessAction.RUN -> canRunReport(report)
            AccessAction.EDIT -> canEditReport(report)
            AccessAction.DELETE -> canDeleteReport(report)
        }
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissao para acessar o relatorio")
    }

    private fun canAccessFolder(folderId: UUID, action: AccessAction): Boolean {
        val ctx = currentAccessContext()
        if (ctx.isAdmin) return true

        val entries = folderAclRepository.findAllByFolderId(folderId)
        if (entries.isEmpty()) {
            val allowed = !properties.security.aclDefaultDeny
            if (!allowed) {
                log.debug("ACL deny folder={} action={} userId={} email={} reason=no_acl_entries_default_deny", folderId, action, ctx.userId, ctx.email)
            }
            return allowed
        }

        val allowed = entries.any { entry ->
            subjectMatches(entry.subjectType, entry.subjectKey, ctx) && actionAllowed(
                canView = entry.canView,
                canRun = entry.canRun,
                canEdit = entry.canEdit,
                canDelete = entry.canDelete,
                action = action
            )
        }
        if (!allowed) {
            log.debug("ACL deny folder={} action={} userId={} email={} reason=no_matching_subject_or_action", folderId, action, ctx.userId, ctx.email)
        }
        return allowed
    }

    private fun canAccessReport(report: ReportEntity, action: AccessAction): Boolean {
        val reportId = report.id ?: return false
        val ctx = currentAccessContext()
        if (ctx.isAdmin) return true

        val reportEntries = reportAclRepository.findAllByReportId(reportId)
        val folderId = report.folder?.id
        val folderEntries = if (folderId != null) folderAclRepository.findAllByFolderId(folderId) else emptyList()

        if (reportEntries.isEmpty() && folderEntries.isEmpty()) {
            val allowed = !properties.security.aclDefaultDeny
            if (!allowed) {
                log.debug("ACL deny report={} action={} userId={} email={} reason=no_acl_entries_default_deny", reportId, action, ctx.userId, ctx.email)
            }
            return allowed
        }

        val allowedByReport = reportEntries.any { entry ->
            subjectMatches(entry.subjectType, entry.subjectKey, ctx) && actionAllowed(
                canView = entry.canView,
                canRun = entry.canRun,
                canEdit = entry.canEdit,
                canDelete = entry.canDelete,
                action = action
            )
        }

        val allowedByFolder = folderEntries.any { entry ->
            subjectMatches(entry.subjectType, entry.subjectKey, ctx) && actionAllowed(
                canView = entry.canView,
                canRun = entry.canRun,
                canEdit = entry.canEdit,
                canDelete = entry.canDelete,
                action = action
            )
        }

        val allowed = allowedByReport || allowedByFolder
        if (!allowed) {
            log.debug("ACL deny report={} action={} userId={} email={} reason=no_matching_subject_or_action", reportId, action, ctx.userId, ctx.email)
        }
        return allowed
    }

    private fun actionAllowed(
        canView: Boolean,
        canRun: Boolean,
        canEdit: Boolean,
        canDelete: Boolean,
        action: AccessAction
    ): Boolean = when (action) {
        AccessAction.VIEW -> canView || canRun || canEdit || canDelete
        AccessAction.RUN -> canRun || canEdit || canDelete
        AccessAction.EDIT -> canEdit || canDelete
        AccessAction.DELETE -> canDelete
    }

    private fun subjectMatches(subjectType: String, subjectKey: String, ctx: AccessContext): Boolean {
        val normalizedType = subjectType.trim().uppercase()
        return when (normalizedType) {
            "USER" -> {
                val normalizedKey = subjectKey.trim()
                normalizedKey.equals(ctx.userId.toString(), ignoreCase = true) ||
                    normalizedKey.equals(ctx.email, ignoreCase = true)
            }
            "ROLE" -> {
                val normalizedKey = normalizeRoleName(subjectKey)
                ctx.roleNames.contains(normalizedKey)
            }
            else -> false
        }
    }

    private fun currentAccessContext(): AccessContext {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado")

        val principal = authentication.principal as? AuthUserPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado")

        val roles = authentication.authorities
            .asSequence()
            .map { it.authority }
            .filter { it.startsWith("ROLE_") }
            .map { it.removePrefix("ROLE_").uppercase() }
            .toSet()

        return AccessContext(
            userId = principal.userId,
            email = principal.username,
            roleNames = roles,
            isAdmin = roles.contains("ADMIN")
        )
    }

    private fun normalizeRoleName(rawRole: String): String =
        rawRole
            .trim()
            .uppercase()
            .removePrefix("ROLE_")

    private data class AccessContext(
        val userId: UUID,
        val email: String,
        val roleNames: Set<String>,
        val isAdmin: Boolean
    )
}

enum class AccessAction {
    VIEW,
    RUN,
    EDIT,
    DELETE
}
