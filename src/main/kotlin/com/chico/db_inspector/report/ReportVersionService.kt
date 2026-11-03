package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Historico de versoes de um relatorio (consulta, variaveis e JRXML).
 *
 * O ReportEntity e a versao em uso. Rascunho nunca toca nele, exceto quando o relatorio inteiro
 * ainda e rascunho (nunca publicado): ai ele guarda o conteudo do rascunho para poder ser
 * executado em previa por quem edita. Publicar copia a versao para o ReportEntity.
 */
@Service
class ReportVersionService(
    private val versionRepository: ReportVersionRepository,
    private val reportRepository: ReportRepository,
    private val folderRepository: ReportFolderRepository,
    private val jasperTemplateRepository: ReportJasperTemplateRepository,
    private val variableService: ReportVariableService,
    private val accessControl: ReportAccessControlService,
    private val auditService: AdminAuditService,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val AI_AGENT_AUTHORITY = "ROLE_AI_AGENT"
        private const val WRITES_LOCK_KEY = 7_105_001L
        private const val BASELINE_NOTE = "Versao inicial (estado anterior ao versionamento)"
        private val variablesType = object : TypeReference<List<ReportVariableRequest>>() {}
    }

    fun openDraftNumbers(): Map<UUID, Int> =
        versionRepository.findOpenDraftNumbers().associate { row -> row[0] as UUID to row[1] as Int }

    @Transactional(readOnly = true)
    fun list(reportId: UUID): List<ReportVersionSummaryResponse> {
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.VIEW)
        val canEdit = accessControl.canEditReport(report)
        requireVisible(report, canEdit)
        return versionRepository.findAllByReportIdOrderByVersionNumberDesc(reportId)
            .filter { canEdit || it.status in visibleToReaders }
            .map { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun get(reportId: UUID, versionNumber: Int): ReportVersionResponse {
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.VIEW)
        requireVisible(report, accessControl.canEditReport(report))
        val version = findVersion(reportId, versionNumber)
        if (version.status !in visibleToReaders) {
            accessControl.requireReportAccess(report, AccessAction.EDIT)
        }
        return version.toResponse(reportId)
    }

    /** Cria v1 com o estado atual se o relatorio ainda nao tem historico. */
    @Transactional
    fun ensureBaseline(report: ReportEntity, jrxmlOverride: String? = null) {
        val reportId = report.id ?: return
        if (versionRepository.existsByReportId(reportId)) return
        val content = snapshot(report, jrxmlOverride)
        versionRepository.save(
            newVersion(report, 1, content, ReportVersionStatus.PUBLISHED).apply {
                authorType = ReportVersionAuthorType.SYSTEM.name
                authorId = null
                authorName = "sistema"
                note = BASELINE_NOTE
                publishedAt = report.updatedAt ?: Instant.now()
                publishedBy = "sistema"
            }
        )
    }

    /** Registra o estado atual do ReportEntity como versao publicada (edicao direta pela tela). */
    @Transactional
    fun recordPublished(report: ReportEntity, note: String? = null): ReportVersionEntity {
        val reportId = report.id ?: error("Relatorio sem id")
        val number = versionRepository.maxVersionNumber(reportId) + 1
        supersedePublished(reportId)
        val author = currentAuthor()
        return versionRepository.save(
            newVersion(report, number, snapshot(report), ReportVersionStatus.PUBLISHED).apply {
                this.note = note?.trim().takeUnless { it.isNullOrBlank() }
                publishedAt = Instant.now()
                publishedBy = author.name
            }
        )
    }

    /**
     * Grava o rascunho de um relatorio ja existente. Existe no maximo um rascunho aberto por
     * relatorio: um segundo pedido atualiza o mesmo, sem consumir numero de versao.
     */
    @Transactional
    fun saveDraft(reportId: UUID, content: ReportVersionContent, note: String?): ReportVersionResponse {
        lockReport(reportId)
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.EDIT)
        val normalized = validateContent(content, report.folder?.id)
        ensureBaseline(report)

        val author = currentAuthor()
        val previous = versionRepository.findFirstByReportIdAndStatus(reportId, ReportVersionStatus.DRAFT.name)
        // Cada gravacao e uma versao: o rascunho anterior vira historico (REPLACED_DRAFT), nao e sobrescrito.
        previous?.let { replaceDraft(it) }
        val version = newVersion(
            report = report,
            number = versionRepository.maxVersionNumber(reportId) + 1,
            content = normalized,
            status = ReportVersionStatus.DRAFT
        ).apply {
            baseVersionNumber = previous?.baseVersionNumber
                ?: versionRepository.findFirstByReportIdAndStatus(reportId, ReportVersionStatus.PUBLISHED.name)?.versionNumber
            authorType = author.type.name
            authorId = author.id
            authorName = author.name
            this.note = note?.trim().takeUnless { it.isNullOrBlank() }
        }
        val saved = versionRepository.save(version)

        // Relatorio nunca publicado: o proprio ReportEntity acompanha o rascunho, para a previa.
        if (report.draft) {
            applyToReport(report, normalized)
            reportRepository.save(report)
        }
        return saved.toResponse(reportId)
    }

    /** Relatorio nunca publicado editado pela tela: o rascunho aberto acompanha a edicao. */
    @Transactional
    fun syncDraft(report: ReportEntity) {
        val reportId = report.id ?: error("Relatorio sem id")
        val author = currentAuthor()
        val previous = versionRepository.findFirstByReportIdAndStatus(reportId, ReportVersionStatus.DRAFT.name)
        previous?.let { replaceDraft(it) }
        versionRepository.save(
            newVersion(report, versionRepository.maxVersionNumber(reportId) + 1, snapshot(report), ReportVersionStatus.DRAFT).apply {
                baseVersionNumber = previous?.baseVersionNumber
                authorType = author.type.name
                authorId = author.id
                authorName = author.name
            }
        )
    }

    /** Cria um relatorio novo ja como rascunho (invisivel para quem so executa). */
    @Transactional
    fun createDraftReport(content: ReportVersionContent, note: String?): ReportVersionResponse {
        lockWrites()
        content.folderId?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }
        val normalized = validateContent(content, null)
        // Salva antes de aplicar o conteudo: o template criado precisa saber de qual relatorio e.
        val saved = reportRepository.save(
            ReportEntity(name = normalized.name, templateName = normalized.name, sql = normalized.sql, draft = true)
        )
        applyToReport(saved, normalized)
        reportRepository.save(saved)
        val reportId = saved.id ?: error("Relatorio sem id")
        val author = currentAuthor()
        val version = versionRepository.save(
            newVersion(saved, 1, snapshot(saved), ReportVersionStatus.DRAFT).apply {
                this.note = note?.trim().takeUnless { it.isNullOrBlank() }
                authorType = author.type.name
                authorId = author.id
                authorName = author.name
            }
        )
        return version.toResponse(reportId)
    }

    /**
     * Publica o rascunho. `expectedRevision` e obrigatorio: o rascunho e regravado no mesmo numero
     * (a IA salva de novo), entao so o numero nao garante que a pessoa publique o que revisou.
     */
    @Transactional
    fun publish(reportId: UUID, versionNumber: Int, note: String?, expectedRevision: Long?): ReportVersionResponse {
        requireHuman("publicar")
        lockReport(reportId)
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.EDIT)
        val version = findVersion(reportId, versionNumber)
        if (version.status == ReportVersionStatus.REPLACED_DRAFT.name) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "O rascunho v$versionNumber foi substituido por uma versao mais nova. Atualize e revise antes de publicar."
            )
        }
        if (version.status != ReportVersionStatus.DRAFT.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Somente rascunho pode ser publicado")
        }
        if (expectedRevision == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe expectedRevision da versao revisada")
        }
        if (expectedRevision != version.revision) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "O rascunho v$versionNumber mudou depois que foi aberto. Atualize e revise antes de publicar."
            )
        }
        val content = version.content()
        content.folderId?.takeIf { it != report.folder?.id }
            ?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }

        applyToReport(report, content)
        report.draft = false
        reportRepository.save(report)

        supersedePublished(reportId)
        val author = currentAuthor()
        version.status = ReportVersionStatus.PUBLISHED.name
        version.publishedAt = Instant.now()
        version.publishedBy = author.name
        version.jasperTemplateId = report.jasperTemplate?.id
        note?.trim()?.takeIf { it.isNotBlank() }?.let { version.note = listOfNotNull(version.note, it).joinToString("\n") }
        val saved = versionRepository.save(version)

        auditService.log(
            action = "REPORT_VERSION_PUBLISH",
            targetType = "REPORT",
            targetId = reportId.toString(),
            details = mapOf("versionNumber" to versionNumber, "author" to version.authorName)
        )
        return saved.toResponse(reportId)
    }

    /**
     * Descarta um rascunho. Se o relatorio nunca foi publicado e nao sobra rascunho, o relatorio
     * inteiro e removido. Devolve true nesse caso.
     */
    @Transactional
    fun discard(reportId: UUID, versionNumber: Int): Boolean {
        lockReport(reportId)
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.EDIT)
        val version = findVersion(reportId, versionNumber)
        if (version.status != ReportVersionStatus.DRAFT.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Somente rascunho pode ser descartado")
        }
        version.status = ReportVersionStatus.DISCARDED.name
        version.updatedAt = Instant.now()
        versionRepository.save(version)

        auditService.log(
            action = "REPORT_VERSION_DISCARD",
            targetType = "REPORT",
            targetId = reportId.toString(),
            details = mapOf("versionNumber" to versionNumber)
        )

        if (report.draft) {
            val template = report.jasperTemplate
            versionRepository.deleteAllByReportId(reportId)
            reportRepository.delete(report)
            // So sai junto o template criado para este rascunho e que ninguem mais usa; template
            // reaproveitado (criado na tela ou por outro relatorio) fica.
            template?.id?.takeIf { template.originReportId == reportId }?.let { templateId ->
                if (reportRepository.findAllByJasperTemplateId(templateId).none { it.id != reportId }) {
                    jasperTemplateRepository.deleteById(templateId)
                }
            }
            return true
        }
        return false
    }

    /**
     * Retoma uma versao antiga como rascunho: copia o conteudo dela para um rascunho novo (o rascunho
     * aberto, se houver, vira historico). Nao publica nada; publicar continua sendo um passo a parte.
     */
    @Transactional
    fun reuseAsDraft(reportId: UUID, versionNumber: Int, note: String?): ReportVersionResponse {
        requireHuman("retomar versoes")
        lockReport(reportId)
        val source = findVersion(reportId, versionNumber)
        if (source.status == ReportVersionStatus.DRAFT.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Esta versao ja e o rascunho aberto")
        }
        return saveDraft(
            reportId,
            source.content(),
            note?.trim().takeUnless { it.isNullOrBlank() } ?: "Retomada da versao $versionNumber"
        )
    }

    private fun replaceDraft(draft: ReportVersionEntity) {
        draft.status = ReportVersionStatus.REPLACED_DRAFT.name
        draft.updatedAt = Instant.now()
        versionRepository.save(draft)
    }

    /** Volta para uma versao antiga publicando uma copia dela como versao nova. */
    @Transactional
    fun restore(reportId: UUID, versionNumber: Int, note: String?): ReportVersionResponse {
        requireHuman("restaurar versoes")
        lockReport(reportId)
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.EDIT)
        val source = findVersion(reportId, versionNumber)
        if (source.status !in visibleToReaders) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Somente versao ja publicada pode ser restaurada")
        }
        val content = source.content()
        content.folderId?.takeIf { it != report.folder?.id }
            ?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }

        applyToReport(report, content)
        report.draft = false
        reportRepository.save(report)

        val number = versionRepository.maxVersionNumber(reportId) + 1
        supersedePublished(reportId)
        val author = currentAuthor()
        val saved = versionRepository.save(
            newVersion(report, number, snapshot(report), ReportVersionStatus.PUBLISHED).apply {
                baseVersionNumber = versionNumber
                this.note = note?.trim().takeUnless { it.isNullOrBlank() } ?: "Restaurada da versao $versionNumber"
                publishedAt = Instant.now()
                publishedBy = author.name
            }
        )
        auditService.log(
            action = "REPORT_VERSION_RESTORE",
            targetType = "REPORT",
            targetId = reportId.toString(),
            details = mapOf("restoredFrom" to versionNumber, "versionNumber" to number)
        )
        return saved.toResponse(reportId)
    }

    /**
     * Template editado direto na tela de templates: registra uma versao em cada relatorio que o usa,
     * para o historico refletir o JRXML em vigor. A v1 guarda o JRXML anterior.
     */
    @Transactional
    fun recordTemplateChange(templateId: UUID, previousJrxml: String, templateName: String) {
        // So os ids: cada relatorio e lido ja travado, para a decisao usar o estado atual.
        reportRepository.findIdsByJasperTemplateId(templateId).forEach { reportId ->
            lockReport(reportId)
            val report = findReport(reportId)
            if (report.draft) {
                // Relatorio nunca publicado continua rascunho: nada de versao PUBLISHED visivel a leitores.
                syncDraft(report)
            } else {
                ensureBaseline(report, jrxmlOverride = previousJrxml)
                recordPublished(report, "Template '$templateName' alterado")
            }
        }
    }

    @Transactional
    fun deleteAll(reportId: UUID) {
        versionRepository.deleteAllByReportId(reportId)
    }

    /** Monta um ReportEntity transiente com o conteudo de uma versao, para previa de rascunho. */
    fun transientReport(reportId: UUID, versionNumber: Int): ReportEntity {
        val report = findReport(reportId)
        accessControl.requireReportAccess(report, AccessAction.EDIT)
        val content = findVersion(reportId, versionNumber).content()
        return ReportEntity(
            id = report.id,
            name = content.name,
            templateName = report.templateName,
            sql = content.sql,
            description = content.description,
            secondSql = content.secondSql,
            comparisonKey = content.comparisonKey,
            draft = true,
            folder = report.folder,
            jasperTemplate = content.jrxml?.let { jrxml ->
                ReportJasperTemplateEntity(id = content.jasperTemplateId, name = content.name, jrxml = jrxml)
            } ?: content.jasperTemplateId?.let { templateId -> jasperTemplateRepository.findById(templateId).orElse(null) }
        ).also { transient ->
            transient.variables.addAll(variableService.normalizeVariables(content.variables))
        }
    }

    private fun validateContent(content: ReportVersionContent, currentFolderId: UUID?): ReportVersionContent {
        val name = content.name.trim()
        require(name.isNotBlank()) { "Nome do relatorio nao pode ser vazio" }
        val sql = content.sql.trim()
        require(sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(sql)
        val secondSql = content.secondSql?.trim().takeUnless { it.isNullOrBlank() }
        secondSql?.let { ReadOnlySqlValidator.requireReadOnly(it) }
        variableService.normalizeVariables(content.variables)
        content.folderId?.takeIf { it != currentFolderId }?.let { folderId ->
            if (!folderRepository.existsById(folderId)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
            }
            accessControl.requireFolderAccess(folderId, AccessAction.EDIT)
        }
        // Com o JRXML na versao, template excluido nao impede: resolveTemplate o recria a partir dele.
        content.jasperTemplateId?.takeIf { content.jrxml.isNullOrBlank() }?.let { templateId ->
            if (!jasperTemplateRepository.existsById(templateId)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
            }
        }
        return content.copy(
            name = name,
            description = content.description?.trim().takeUnless { it.isNullOrBlank() },
            sql = sql,
            secondSql = secondSql,
            comparisonKey = content.comparisonKey?.trim().takeUnless { it.isNullOrBlank() },
            jrxml = content.jrxml?.trim().takeUnless { it.isNullOrBlank() }
        )
    }

    /** Copia o conteudo de uma versao para o relatorio em uso, inclusive o JRXML. */
    private fun applyToReport(report: ReportEntity, content: ReportVersionContent) {
        report.name = content.name
        report.description = content.description
        report.sql = content.sql
        report.secondSql = content.secondSql
        report.comparisonKey = content.comparisonKey
        report.folder = content.folderId?.let { folderId ->
            folderRepository.findById(folderId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
            }
        }
        report.folder?.let { report.templateName = it.name }
        report.replaceVariables(variableService.normalizeVariables(content.variables))
        report.jasperTemplate = resolveTemplate(report, content)
    }

    /**
     * Decide qual template o relatorio passa a usar:
     *  - sem JRXML: o template referenciado (ou nenhum);
     *  - JRXML igual ao do template referenciado: o proprio template;
     *  - JRXML diferente: atualiza no lugar SO o template que este relatorio ja usa e que nenhum outro
     *    usa. Template referenciado de outro lugar (mesmo sem vinculos) nunca e sobrescrito: vira copia.
     *  - template referenciado que foi excluido, com o JRXML guardado na versao: recria a partir dele.
     */
    private fun resolveTemplate(report: ReportEntity, content: ReportVersionContent): ReportJasperTemplateEntity? {
        val referenced = content.jasperTemplateId?.let { jasperTemplateRepository.findById(it).orElse(null) }
            // Trava o template citado: ninguem altera ou conta vinculos dele no meio desta decisao.
            ?.also { template -> template.id?.let { jasperTemplateRepository.lockById(it) } }
        val jrxml = content.jrxml
        if (jrxml == null) {
            if (content.jasperTemplateId != null && referenced == null) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
            }
            return referenced
        }
        if (referenced != null && referenced.jrxml.trim() == jrxml) return referenced

        val current = report.jasperTemplate
        val currentId = current?.id
        // Trava o template antes de contar vinculos: ninguem vincula outro relatorio a ele no meio da decisao.
        currentId?.let { jasperTemplateRepository.lockById(it) }
        val ownedOnlyByThisReport = currentId != null && report.id != null &&
            (referenced == null || referenced.id == currentId) &&
            reportRepository.findAllByJasperTemplateId(currentId).let { links ->
                links.isNotEmpty() && links.all { it.id == report.id }
            }
        if (current != null && ownedOnlyByThisReport) {
            current.jrxml = jrxml
            return jasperTemplateRepository.save(current)
        }
        return jasperTemplateRepository.save(
            ReportJasperTemplateEntity(
                name = uniqueTemplateName(content.name),
                description = "Template do relatorio '${content.name}'",
                jrxml = jrxml,
                originReportId = report.id
            )
        )
    }

    /**
     * Serializa as gravacoes de versao do mesmo relatorio (numero da versao, revisao do rascunho).
     * Chamar ANTES de ler o relatorio na transacao, para a leitura ja vir travada e atual.
     */
    fun lockReport(reportId: UUID) {
        lockWrites()
        reportRepository.lockById(reportId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
    }

    /**
     * Primeira trava de TODA gravacao que mexe em versao ou template (relatorio, template, vinculo).
     * Como todo caminho a toma antes de qualquer trava de linha, a ordem de aquisicao e a mesma em
     * todos e nao ha deadlock entre editar template e editar relatorio. Serializa operacoes raras e curtas.
     */
    fun lockWrites() {
        reportRepository.advisoryLock(WRITES_LOCK_KEY)
    }

    /** Relatorio nunca publicado so existe para quem edita: o historico dele tambem. */
    private fun requireVisible(report: ReportEntity, canEdit: Boolean) {
        if (report.draft && !canEdit) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
    }

    private fun uniqueTemplateName(reportName: String): String {
        val base = reportName.trim().take(200)
        if (!jasperTemplateRepository.existsByNameIgnoreCase(base)) return base
        return generateSequence(2) { it + 1 }
            .map { "$base ($it)" }
            .first { !jasperTemplateRepository.existsByNameIgnoreCase(it) }
    }

    private fun supersedePublished(reportId: UUID) {
        versionRepository.findAllByReportIdAndStatus(reportId, ReportVersionStatus.PUBLISHED.name).forEach {
            it.status = ReportVersionStatus.SUPERSEDED.name
            versionRepository.save(it)
        }
    }

    private fun snapshot(report: ReportEntity, jrxmlOverride: String? = null) = ReportVersionContent(
        name = report.name,
        description = report.description,
        folderId = report.folder?.id,
        sql = report.sql,
        secondSql = report.secondSql,
        comparisonKey = report.comparisonKey,
        variables = report.variables.sortedBy { it.orderIndex }.map { it.toRequest() },
        jasperTemplateId = report.jasperTemplate?.id,
        jrxml = jrxmlOverride ?: report.jasperTemplate?.jrxml
    )

    private fun newVersion(
        report: ReportEntity,
        number: Int,
        content: ReportVersionContent,
        status: ReportVersionStatus
    ): ReportVersionEntity {
        val author = currentAuthor()
        return ReportVersionEntity(
            report = report,
            versionNumber = number,
            status = status.name,
            authorType = author.type.name,
            authorId = author.id,
            authorName = author.name
        ).apply { applyContent(content) }
    }

    private fun ReportVersionEntity.applyContent(content: ReportVersionContent) {
        name = content.name
        description = content.description
        folderId = content.folderId
        sql = content.sql
        secondSql = content.secondSql
        comparisonKey = content.comparisonKey
        variablesJson = objectMapper.writeValueAsString(content.variables)
        jasperTemplateId = content.jasperTemplateId
        jrxml = content.jrxml
    }

    private fun ReportVersionEntity.content() = ReportVersionContent(
        name = name,
        description = description,
        folderId = folderId,
        sql = sql,
        secondSql = secondSql,
        comparisonKey = comparisonKey,
        variables = objectMapper.readValue(variablesJson, variablesType),
        jasperTemplateId = jasperTemplateId,
        jrxml = jrxml
    )

    private fun ReportVariableEntity.toRequest() = ReportVariableRequest(
        key = key,
        label = label,
        type = type,
        required = required,
        multiple = multiple,
        defaultValue = defaultValue,
        optionsSql = optionsSql,
        orderIndex = orderIndex
    )

    private fun ReportVersionEntity.toSummary() = ReportVersionSummaryResponse(
        versionNumber = versionNumber,
        status = status,
        name = name,
        baseVersionNumber = baseVersionNumber,
        authorType = authorType,
        authorName = authorName,
        note = note,
        hasJrxml = !jrxml.isNullOrBlank(),
        createdAt = (createdAt ?: Instant.now()).toEpochMilli(),
        updatedAt = updatedAt?.toEpochMilli(),
        publishedAt = publishedAt?.toEpochMilli(),
        publishedBy = publishedBy,
        revision = revision
    )

    private fun ReportVersionEntity.toResponse(reportId: UUID): ReportVersionResponse {
        val content = content()
        return ReportVersionResponse(
            reportId = reportId.toString(),
            versionNumber = versionNumber,
            status = status,
            baseVersionNumber = baseVersionNumber,
            name = content.name,
            description = content.description,
            folderId = content.folderId?.toString(),
            sql = content.sql,
            secondSql = content.secondSql,
            comparisonKey = content.comparisonKey,
            variables = content.variables,
            jasperTemplateId = content.jasperTemplateId?.toString(),
            jrxml = content.jrxml,
            authorType = authorType,
            authorName = authorName,
            note = note,
            createdAt = (createdAt ?: Instant.now()).toEpochMilli(),
            updatedAt = updatedAt?.toEpochMilli(),
            publishedAt = publishedAt?.toEpochMilli(),
            publishedBy = publishedBy,
            revision = revision
        )
    }

    private fun findReport(reportId: UUID): ReportEntity =
        reportRepository.findById(reportId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }

    private fun findVersion(reportId: UUID, versionNumber: Int): ReportVersionEntity =
        versionRepository.findByReportIdAndVersionNumber(reportId, versionNumber)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Versao $versionNumber nao encontrada")

    /** Publicar e restaurar decidem o que os usuarios executam: ficam com uma pessoa. */
    private fun requireHuman(action: String) {
        if (currentAuthor().type == ReportVersionAuthorType.AI_TOKEN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Token de IA nao pode $action")
        }
    }

    private data class Author(val type: ReportVersionAuthorType, val id: UUID?, val name: String)

    private fun currentAuthor(): Author {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AuthUserPrincipal
            ?: return Author(ReportVersionAuthorType.SYSTEM, null, "sistema")
        val isAiAgent = authentication.authorities.any { it.authority == AI_AGENT_AUTHORITY }
        return Author(
            type = if (isAiAgent) ReportVersionAuthorType.AI_TOKEN else ReportVersionAuthorType.USER,
            id = principal.userId,
            name = principal.name.ifBlank { principal.username }
        )
    }

    private val visibleToReaders = setOf(ReportVersionStatus.PUBLISHED.name, ReportVersionStatus.SUPERSEDED.name)
}
