package com.chico.dbinspector.report

import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ReportJasperTemplateService(
    private val repository: ReportJasperTemplateRepository,
    private val reportRepository: ReportRepository,
    private val versionService: ReportVersionService
) {
    fun list(): List<JasperTemplateResponse> =
        repository.findAll(Sort.by(Sort.Direction.ASC, "name"))
            .map { it.toResponse() }

    fun get(id: UUID): JasperTemplateResponse =
        repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
        }.toResponse()

    fun create(request: JasperTemplateRequest): JasperTemplateResponse {
        val name = request.name.trim()
        val jrxml = request.jrxml.trim()
        require(name.isNotBlank()) { "Nome do template nao pode ser vazio" }
        require(jrxml.isNotBlank()) { "JRXML nao pode ser vazio" }
        if (repository.existsByNameIgnoreCase(name)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ja existe template com esse nome")
        }

        val entity = ReportJasperTemplateEntity(
            name = name,
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            jrxml = jrxml,
            archived = request.archived ?: false
        )
        return repository.save(entity).toResponse()
    }

    @Transactional
    fun update(id: UUID, request: JasperTemplateRequest): JasperTemplateResponse {
        // Mesma ordem de todos os caminhos (trava de escrita -> linhas): sem deadlock com edicao de relatorio.
        versionService.lockWrites()
        // Trava antes de ler: nenhum relatorio e vinculado a este template durante a edicao e o historico.
        val entity = repository.lockById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")

        val name = request.name.trim()
        val jrxml = request.jrxml.trim()
        require(name.isNotBlank()) { "Nome do template nao pode ser vazio" }
        require(jrxml.isNotBlank()) { "JRXML nao pode ser vazio" }
        if (!entity.name.equals(name, ignoreCase = true) && repository.existsByNameIgnoreCase(name)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ja existe template com esse nome")
        }

        val previousJrxml = entity.jrxml
        entity.name = name
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        entity.jrxml = jrxml
        request.archived?.let { entity.archived = it }
        val saved = repository.save(entity)
        if (previousJrxml.trim() != jrxml) {
            versionService.recordTemplateChange(id, previousJrxml, name)
        }
        return saved.toResponse()
    }

    fun delete(id: UUID) {
        if (!repository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
        }
        if (reportRepository.existsByJasperTemplateId(id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Nao e possivel excluir template vinculado a relatorios")
        }
        repository.deleteById(id)
    }

    private fun ReportJasperTemplateEntity.toResponse(): JasperTemplateResponse {
        val created = createdAt ?: error("createdAt do template ausente")
        val updated = updatedAt ?: error("updatedAt do template ausente")
        return JasperTemplateResponse(
            id = id?.toString() ?: error("id do template ausente"),
            name = name,
            description = description,
            jrxml = jrxml,
            archived = archived,
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }
}
