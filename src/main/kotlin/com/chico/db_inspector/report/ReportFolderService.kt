package com.chico.dbinspector.report

import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ReportFolderService(
    private val folderRepository: ReportFolderRepository,
    private val reportRepository: ReportRepository,
    private val accessControl: ReportAccessControlService
) {
    fun list(): List<ReportFolderResponse> =
        folderRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
            .filter { folder ->
                val folderId = folder.id ?: return@filter false
                accessControl.canViewFolder(folderId)
            }
            .map { it.toResponse() }

    fun create(request: ReportFolderRequest): ReportFolderResponse {
        val folderName = request.name.trim()
        require(folderName.isNotBlank()) { "Nome da pasta nao pode ser vazio" }
        if (folderRepository.existsByNameIgnoreCase(folderName)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ja existe pasta com esse nome")
        }
        val entity = ReportFolderEntity(
            name = folderName,
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            archived = request.archived ?: false
        )
        return folderRepository.save(entity).toResponse()
    }

    fun update(id: UUID, request: ReportFolderRequest): ReportFolderResponse {
        accessControl.requireFolderAccess(id, AccessAction.EDIT)
        val entity = folderRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
        }
        val folderName = request.name.trim()
        require(folderName.isNotBlank()) { "Nome da pasta nao pode ser vazio" }
        if (!entity.name.equals(folderName, ignoreCase = true) && folderRepository.existsByNameIgnoreCase(folderName)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ja existe pasta com esse nome")
        }
        entity.name = folderName
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        request.archived?.let { entity.archived = it }
        return folderRepository.save(entity).toResponse()
    }

    fun delete(id: UUID) {
        accessControl.requireFolderAccess(id, AccessAction.DELETE)
        if (!folderRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
        }
        if (reportRepository.existsByFolderId(id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Nao e possivel excluir pasta com relatorios vinculados")
        }
        folderRepository.deleteById(id)
    }

    private fun ReportFolderEntity.toResponse(): ReportFolderResponse {
        val created = createdAt ?: error("createdAt da pasta ausente")
        val updated = updatedAt ?: error("updatedAt da pasta ausente")
        return ReportFolderResponse(
            id = id?.toString() ?: error("id da pasta ausente"),
            name = name,
            description = description,
            archived = archived,
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }
}
