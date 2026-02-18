package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AppUserRepository
import com.chico.dbinspector.auth.RoleRepository
import jakarta.transaction.Transactional
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ReportAclAdminService(
    private val folderRepository: ReportFolderRepository,
    private val reportRepository: ReportRepository,
    private val folderAclRepository: ReportFolderAclRepository,
    private val reportAclRepository: ReportAclRepository,
    private val userRepository: AppUserRepository,
    private val roleRepository: RoleRepository
) {
    fun listFolderAcl(folderId: UUID): List<ResourceAclResponse> {
        ensureFolder(folderId)
        return folderAclRepository.findAllByFolderId(folderId).map { it.toResponse() }
    }

    fun listReportAcl(reportId: UUID): List<ResourceAclResponse> {
        ensureReport(reportId)
        return reportAclRepository.findAllByReportId(reportId).map { it.toResponse() }
    }

    @Transactional
    fun upsertFolderAcl(folderId: UUID, request: ResourceAclRequest): ResourceAclResponse {
        val folder = ensureFolder(folderId)
        val normalized = normalizeSubject(request.subjectType, request.subject)

        val entity = folderAclRepository.findByFolderIdAndSubjectTypeAndSubjectKeyIgnoreCase(
            folderId,
            normalized.type,
            normalized.key
        ).orElseGet {
            ReportFolderAclEntity(
                folder = folder,
                subjectType = normalized.type,
                subjectKey = normalized.key
            )
        }

        applyPermissions(entity, request)
        return folderAclRepository.save(entity).toResponse()
    }

    @Transactional
    fun upsertReportAcl(reportId: UUID, request: ResourceAclRequest): ResourceAclResponse {
        val report = ensureReport(reportId)
        val normalized = normalizeSubject(request.subjectType, request.subject)

        val entity = reportAclRepository.findByReportIdAndSubjectTypeAndSubjectKeyIgnoreCase(
            reportId,
            normalized.type,
            normalized.key
        ).orElseGet {
            ReportAclEntity(
                report = report,
                subjectType = normalized.type,
                subjectKey = normalized.key
            )
        }

        applyPermissions(entity, request)
        return reportAclRepository.save(entity).toResponse()
    }

    @Transactional
    fun removeFolderAcl(folderId: UUID, subjectType: String, subject: String) {
        ensureFolder(folderId)
        val normalized = normalizeSubject(subjectType, subject)
        val entity = folderAclRepository.findByFolderIdAndSubjectTypeAndSubjectKeyIgnoreCase(
            folderId,
            normalized.type,
            normalized.key
        ).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "ACL da pasta nao encontrada")
        }
        folderAclRepository.delete(entity)
    }

    @Transactional
    fun removeReportAcl(reportId: UUID, subjectType: String, subject: String) {
        ensureReport(reportId)
        val normalized = normalizeSubject(subjectType, subject)
        val entity = reportAclRepository.findByReportIdAndSubjectTypeAndSubjectKeyIgnoreCase(
            reportId,
            normalized.type,
            normalized.key
        ).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "ACL do relatorio nao encontrada")
        }
        reportAclRepository.delete(entity)
    }

    private fun applyPermissions(entity: ReportFolderAclEntity, request: ResourceAclRequest) {
        entity.canView = request.canView
        entity.canRun = request.canRun
        entity.canEdit = request.canEdit
        entity.canDelete = request.canDelete
    }

    private fun applyPermissions(entity: ReportAclEntity, request: ResourceAclRequest) {
        entity.canView = request.canView
        entity.canRun = request.canRun
        entity.canEdit = request.canEdit
        entity.canDelete = request.canDelete
    }

    private fun normalizeSubject(rawType: String, rawSubject: String): NormalizedSubject {
        val type = rawType.trim().uppercase()
        val subject = rawSubject.trim()
        require(subject.isNotBlank()) { "subject obrigatorio" }

        return when (type) {
            "USER" -> {
                val userId = runCatching { UUID.fromString(subject) }.getOrElse {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "subject de USER deve ser UUID")
                }
                if (!userRepository.existsById(userId)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario nao encontrado")
                }
                NormalizedSubject(type = "USER", key = userId.toString())
            }
            "ROLE" -> {
                val role = subject.uppercase()
                if (roleRepository.findByNameIgnoreCase(role).isEmpty) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Role nao encontrada")
                }
                NormalizedSubject(type = "ROLE", key = role)
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType invalido. Use USER ou ROLE")
        }
    }

    private fun ensureFolder(folderId: UUID): ReportFolderEntity =
        folderRepository.findById(folderId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
        }

    private fun ensureReport(reportId: UUID): ReportEntity =
        reportRepository.findById(reportId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }

    private fun ReportFolderAclEntity.toResponse(): ResourceAclResponse =
        ResourceAclResponse(
            id = id?.toString() ?: error("ACL sem id"),
            subjectType = subjectType,
            subject = subjectKey,
            canView = canView,
            canRun = canRun,
            canEdit = canEdit,
            canDelete = canDelete
        )

    private fun ReportAclEntity.toResponse(): ResourceAclResponse =
        ResourceAclResponse(
            id = id?.toString() ?: error("ACL sem id"),
            subjectType = subjectType,
            subject = subjectKey,
            canView = canView,
            canRun = canRun,
            canEdit = canEdit,
            canDelete = canDelete
        )

    private data class NormalizedSubject(
        val type: String,
        val key: String
    )
}
