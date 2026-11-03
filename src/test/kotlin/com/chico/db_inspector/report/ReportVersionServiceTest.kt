package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AdminAuditService
import com.chico.dbinspector.auth.AuthUserPrincipal
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ReportVersionServiceTest {

    private val versionRepository = Mockito.mock(ReportVersionRepository::class.java)
    private val reportRepository = Mockito.mock(ReportRepository::class.java)
    private val folderRepository = Mockito.mock(ReportFolderRepository::class.java)
    private val templateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java)
    private val accessControl = Mockito.mock(ReportAccessControlService::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)

    private val service = ReportVersionService(
        versionRepository = versionRepository,
        reportRepository = reportRepository,
        folderRepository = folderRepository,
        jasperTemplateRepository = templateRepository,
        variableService = ReportVariableService(),
        accessControl = accessControl,
        auditService = auditService,
        objectMapper = jacksonObjectMapper()
    )

    private val versions = mutableListOf<ReportVersionEntity>()
    private val templates = mutableListOf<ReportJasperTemplateEntity>()
    private val reportId = UUID.randomUUID()
    private lateinit var report: ReportEntity

    @BeforeEach
    fun setup() {
        report = ReportEntity(
            id = reportId,
            name = "Colheita",
            templateName = "Colheita",
            sql = "SELECT 1 AS a",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z")
        )
        Mockito.`when`(reportRepository.findById(reportId)).thenAnswer { Optional.of(report) }
        Mockito.`when`(reportRepository.save(Mockito.any(ReportEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<ReportEntity>(0).also { if (it.id == null) it.id = UUID.randomUUID() }
        }
        Mockito.`when`(versionRepository.save(Mockito.any(ReportVersionEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<ReportVersionEntity>(0).also {
                if (it.id == null) {
                    it.id = UUID.randomUUID()
                    it.createdAt = Instant.now()
                    versions += it
                }
            }
        }
        Mockito.`when`(versionRepository.existsByReportId(anyUuid())).thenAnswer { versions.isNotEmpty() }
        Mockito.`when`(versionRepository.maxVersionNumber(anyUuid()))
            .thenAnswer { versions.maxOfOrNull { it.versionNumber } ?: 0 }
        Mockito.`when`(versionRepository.findFirstByReportIdAndStatus(anyUuid(), Mockito.anyString()))
            .thenAnswer { inv -> versions.firstOrNull { it.status == inv.getArgument<String>(1) } }
        Mockito.`when`(versionRepository.findAllByReportIdAndStatus(anyUuid(), Mockito.anyString()))
            .thenAnswer { inv -> versions.filter { it.status == inv.getArgument<String>(1) } }
        Mockito.`when`(versionRepository.findByReportIdAndVersionNumber(anyUuid(), Mockito.anyInt()))
            .thenAnswer { inv -> versions.firstOrNull { it.versionNumber == inv.getArgument<Int>(1) } }
        Mockito.`when`(versionRepository.findAllByReportIdOrderByVersionNumberDesc(anyUuid()))
            .thenAnswer { versions.sortedByDescending { it.versionNumber } }
        Mockito.`when`(templateRepository.save(Mockito.any(ReportJasperTemplateEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<ReportJasperTemplateEntity>(0).also {
                if (it.id == null) {
                    it.id = UUID.randomUUID()
                    templates += it
                }
            }
        }
        Mockito.`when`(accessControl.canEditReport(anyReport())).thenReturn(true)
        Mockito.`when`(reportRepository.lockById(anyUuid())).thenAnswer { ReportEntity() }
        authenticate(ai = false)
    }

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `deve criar versao inicial do sistema so quando o relatorio nao tem historico`() {
        service.ensureBaseline(report)
        service.ensureBaseline(report)

        assertEquals(1, versions.size)
        assertEquals(ReportVersionStatus.PUBLISHED.name, versions[0].status)
        assertEquals(ReportVersionAuthorType.SYSTEM.name, versions[0].authorType)
        assertEquals("SELECT 1 AS a", versions[0].sql)
    }

    @Test
    fun `rascunho de relatorio publicado nao altera o relatorio em uso`() {
        val draft = service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), "teste")

        assertEquals(2, draft.versionNumber)
        assertEquals(1, draft.baseVersionNumber)
        assertEquals(ReportVersionStatus.DRAFT.name, draft.status)
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `cada gravacao do rascunho e uma versao nova e a anterior vira historico`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        val second = service.saveDraft(reportId, content(sql = "SELECT 3 AS c"), "ajuste")

        assertEquals(3, second.versionNumber)
        assertEquals(1, second.baseVersionNumber)
        assertEquals(ReportVersionStatus.REPLACED_DRAFT.name, versions.first { it.versionNumber == 2 }.status)
        assertEquals("SELECT 2 AS b", versions.first { it.versionNumber == 2 }.sql)
        assertEquals(1, versions.count { it.status == ReportVersionStatus.DRAFT.name })
    }

    @Test
    fun `rascunho deve recusar SQL de escrita`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.saveDraft(reportId, content(sql = "DELETE FROM agro.fazenda"), null)
        }
        assertTrue(versions.isEmpty())
    }

    @Test
    fun `relatorio novo de IA nasce como rascunho com autor token`() {
        authenticate(ai = true)

        val created = service.createDraftReport(content(sql = "SELECT 9 AS z"), "hipoteses testadas")

        assertEquals(1, created.versionNumber)
        assertEquals(ReportVersionStatus.DRAFT.name, created.status)
        assertEquals(ReportVersionAuthorType.AI_TOKEN.name, created.authorType)
        assertEquals("hipoteses testadas", created.note)
    }

    @Test
    fun `publicar copia o rascunho para o relatorio e substitui a versao anterior`() {
        report.draft = true
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)

        val published = service.publish(reportId, versions.last().versionNumber, "revisado", revisionOf(versions.last().versionNumber))

        assertEquals(ReportVersionStatus.PUBLISHED.name, published.status)
        assertEquals("SELECT 2 AS b", report.sql)
        assertFalse(report.draft)
        assertEquals(1, versions.count { it.status == ReportVersionStatus.PUBLISHED.name })
    }

    @Test
    fun `token de IA nao pode publicar`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        authenticate(ai = true)

        val ex = assertThrows(ResponseStatusException::class.java) { service.publish(reportId, 2, null, revisionOf(2)) }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `publicar versao que nao e rascunho devolve conflito`() {
        service.ensureBaseline(report)

        val ex = assertThrows(ResponseStatusException::class.java) { service.publish(reportId, 1, null, revisionOf(1)) }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `publicar exige a revisao que a pessoa viu`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)

        val ex = assertThrows(ResponseStatusException::class.java) { service.publish(reportId, 2, null, null) }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `rascunho regravado depois de aberto nao e publicado`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        val seen = revisionOf(2)
        service.saveDraft(reportId, content(sql = "SELECT 3 AS c"), null)

        val ex = assertThrows(ResponseStatusException::class.java) { service.publish(reportId, 2, null, seen) }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertTrue(ex.reason!!.contains("substituido"))
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `retomar versao antiga cria rascunho novo sem publicar`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        service.saveDraft(reportId, content(sql = "SELECT 3 AS c"), null)

        val reused = service.reuseAsDraft(reportId, 2, null)

        assertEquals(4, reused.versionNumber)
        assertEquals(ReportVersionStatus.DRAFT.name, reused.status)
        assertEquals("SELECT 2 AS b", reused.sql)
        assertEquals("Retomada da versao 2", reused.note)
        assertEquals(ReportVersionStatus.REPLACED_DRAFT.name, versions.first { it.versionNumber == 3 }.status)
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `nao retoma o proprio rascunho aberto nem como token de IA`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException::class.java) { service.reuseAsDraft(reportId, 2, null) }.statusCode)
        authenticate(ai = true)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException::class.java) { service.reuseAsDraft(reportId, 1, null) }.statusCode)
    }

    @Test
    fun `historico de rascunhos substituidos so aparece para quem edita`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        service.saveDraft(reportId, content(sql = "SELECT 3 AS c"), null)
        Mockito.`when`(accessControl.canEditReport(report)).thenReturn(false)

        assertEquals(listOf(1), service.list(reportId).map { it.versionNumber })
    }

    @Test
    fun `JRXML alterado em template exclusivo atualiza o proprio template`() {
        val templateId = UUID.randomUUID()
        val template = ReportJasperTemplateEntity(id = templateId, name = "colheita", jrxml = "<old/>")
        report.jasperTemplate = template
        Mockito.`when`(templateRepository.existsById(templateId)).thenReturn(true)
        Mockito.`when`(templateRepository.findById(templateId)).thenReturn(Optional.of(template))
        Mockito.`when`(reportRepository.findAllByJasperTemplateId(templateId)).thenReturn(listOf(report))

        service.saveDraft(reportId, content(templateId = templateId, jrxml = "<new/>"), null)
        service.publish(reportId, 2, null, revisionOf(2))

        assertSame(template, report.jasperTemplate)
        assertEquals("<new/>", template.jrxml)
        assertEquals("<old/>", versions.first { it.versionNumber == 1 }.jrxml)
    }

    @Test
    fun `JRXML alterado em template compartilhado cria template proprio`() {
        val sharedId = UUID.randomUUID()
        val shared = ReportJasperTemplateEntity(id = sharedId, name = "base", jrxml = "<old/>")
        val other = ReportEntity(id = UUID.randomUUID(), name = "Outro", templateName = "x", sql = "SELECT 1")
        report.jasperTemplate = shared
        Mockito.`when`(templateRepository.existsById(sharedId)).thenReturn(true)
        Mockito.`when`(templateRepository.findById(sharedId)).thenReturn(Optional.of(shared))
        Mockito.`when`(reportRepository.findAllByJasperTemplateId(sharedId)).thenReturn(listOf(report, other))

        service.saveDraft(reportId, content(templateId = sharedId, jrxml = "<new/>"), null)
        service.publish(reportId, 2, null, revisionOf(2))

        assertNotSame(shared, report.jasperTemplate)
        assertEquals("<new/>", report.jasperTemplate?.jrxml)
        assertEquals("<old/>", shared.jrxml)
    }

    @Test
    fun `descartar unico rascunho de relatorio nunca publicado remove o relatorio`() {
        val newId = UUID.randomUUID()
        var created: ReportEntity? = null
        Mockito.`when`(reportRepository.save(Mockito.any(ReportEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<ReportEntity>(0).also {
                if (it.id == null) it.id = newId
                created = it
            }
        }
        Mockito.`when`(reportRepository.findById(newId)).thenAnswer { Optional.ofNullable(created) }
        authenticate(ai = true)
        service.createDraftReport(content(), null)
        authenticate(ai = false)

        val deleted = service.discard(newId, 1)

        assertTrue(deleted)
        assertTrue(created?.draft == true)
        Mockito.verify(versionRepository).deleteAllByReportId(newId)
    }

    @Test
    fun `descartar relatorio nunca publicado remove tambem o template criado para ele`() {
        val templateId = UUID.randomUUID()
        report.draft = true
        Mockito.`when`(reportRepository.findAllByJasperTemplateId(templateId)).thenReturn(listOf(report))
        service.saveDraft(reportId, content(), null)
        report.jasperTemplate = ReportJasperTemplateEntity(id = templateId, name = "rascunho", jrxml = "<x/>", originReportId = reportId)

        assertTrue(service.discard(reportId, versions.single { it.status == ReportVersionStatus.DRAFT.name }.versionNumber))

        Mockito.verify(templateRepository).deleteById(templateId)
    }

    @Test
    fun `descartar relatorio nunca publicado preserva template reaproveitado`() {
        val templateId = UUID.randomUUID()
        report.draft = true
        Mockito.`when`(reportRepository.findAllByJasperTemplateId(templateId)).thenReturn(listOf(report))
        service.saveDraft(reportId, content(), null)
        report.jasperTemplate = ReportJasperTemplateEntity(id = templateId, name = "da tela", jrxml = "<x/>", originReportId = null)

        assertTrue(service.discard(reportId, versions.single { it.status == ReportVersionStatus.DRAFT.name }.versionNumber))

        Mockito.verify(templateRepository, Mockito.never()).deleteById(templateId)
    }

    @Test
    fun `rascunho que cita template sem vinculos nao sobrescreve o template`() {
        val unusedId = UUID.randomUUID()
        val unused = ReportJasperTemplateEntity(id = unusedId, name = "modelo", jrxml = "<modelo/>")
        Mockito.`when`(templateRepository.existsById(unusedId)).thenReturn(true)
        Mockito.`when`(templateRepository.findById(unusedId)).thenReturn(Optional.of(unused))
        Mockito.`when`(reportRepository.findAllByJasperTemplateId(unusedId)).thenReturn(emptyList())
        authenticate(ai = true)

        service.createDraftReport(content(templateId = unusedId, jrxml = "<alterado/>"), null)

        assertEquals("<modelo/>", unused.jrxml)
        assertEquals(1, templates.size)
        assertEquals("<alterado/>", templates.single().jrxml)
    }

    @Test
    fun `restaurar versao cujo template foi excluido recria a partir do JRXML guardado`() {
        val goneId = UUID.randomUUID()
        service.ensureBaseline(report)
        versions.single().apply {
            jasperTemplateId = goneId
            jrxml = "<antigo/>"
        }
        Mockito.`when`(templateRepository.findById(goneId)).thenReturn(Optional.empty())
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        service.publish(reportId, 2, null, revisionOf(2))

        service.restore(reportId, 1, null)

        assertEquals("<antigo/>", report.jasperTemplate?.jrxml)
    }

    @Test
    fun `retomar versao cujo template foi excluido recria o template a partir do JRXML guardado`() {
        val goneId = UUID.randomUUID()
        service.ensureBaseline(report)
        versions.single().apply {
            jasperTemplateId = goneId
            jrxml = "<antigo/>"
        }
        Mockito.`when`(templateRepository.existsById(goneId)).thenReturn(false)
        Mockito.`when`(templateRepository.findById(goneId)).thenReturn(Optional.empty())
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)

        val reused = service.reuseAsDraft(reportId, 1, null)

        assertEquals("<antigo/>", reused.jrxml)
        assertEquals(ReportVersionStatus.DRAFT.name, reused.status)
    }

    @Test
    fun `criar rascunho trava a escrita antes de resolver o template`() {
        service.createDraftReport(content(), null)

        Mockito.verify(reportRepository).advisoryLock(anyLong())
    }

    @Test
    fun `previa de rascunho que so cita templateId usa o template`() {
        val templateId = UUID.randomUUID()
        val template = ReportJasperTemplateEntity(id = templateId, name = "colheita", jrxml = "<t/>")
        Mockito.`when`(templateRepository.existsById(templateId)).thenReturn(true)
        Mockito.`when`(templateRepository.findById(templateId)).thenReturn(Optional.of(template))
        service.saveDraft(reportId, content(templateId = templateId), null)

        assertSame(template, service.transientReport(reportId, 2).jasperTemplate)
    }

    @Test
    fun `template alterado mantem relatorio nunca publicado como rascunho`() {
        val templateId = UUID.randomUUID()
        report.draft = true
        Mockito.`when`(reportRepository.findIdsByJasperTemplateId(templateId)).thenReturn(listOf(reportId))

        service.recordTemplateChange(templateId, "<antes/>", "colheita")

        assertTrue(versions.none { it.status == ReportVersionStatus.PUBLISHED.name })
        assertTrue(versions.any { it.status == ReportVersionStatus.DRAFT.name })
    }

    @Test
    fun `historico de relatorio nunca publicado e invisivel para quem nao edita`() {
        report.draft = true
        Mockito.`when`(accessControl.canEditReport(report)).thenReturn(false)

        val ex = assertThrows(ResponseStatusException::class.java) { service.list(reportId) }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `descartar rascunho de relatorio publicado preserva o relatorio`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)

        val deleted = service.discard(reportId, 2)

        assertFalse(deleted)
        assertEquals(ReportVersionStatus.DISCARDED.name, versions.first { it.versionNumber == 2 }.status)
        assertEquals("SELECT 1 AS a", report.sql)
    }

    @Test
    fun `restaurar publica copia da versao antiga como versao nova`() {
        service.ensureBaseline(report)
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        service.publish(reportId, 2, null, revisionOf(2))

        val restored = service.restore(reportId, 1, null)

        assertEquals(3, restored.versionNumber)
        assertEquals(1, restored.baseVersionNumber)
        assertEquals("SELECT 1 AS a", report.sql)
        assertEquals(ReportVersionStatus.SUPERSEDED.name, versions.first { it.versionNumber == 2 }.status)
    }

    @Test
    fun `quem nao edita nao ve rascunhos no historico`() {
        service.saveDraft(reportId, content(sql = "SELECT 2 AS b"), null)
        Mockito.`when`(accessControl.canEditReport(report)).thenReturn(false)

        val listed = service.list(reportId)

        assertEquals(listOf(1), listed.map { it.versionNumber })
    }

    private fun content(
        sql: String = "SELECT 1 AS a",
        templateId: UUID? = null,
        jrxml: String? = null
    ) = ReportVersionContent(
        name = "Colheita",
        description = null,
        folderId = null,
        sql = sql,
        secondSql = null,
        comparisonKey = null,
        variables = emptyList(),
        jasperTemplateId = templateId,
        jrxml = jrxml
    )

    private fun anyLong(): Long {
        Mockito.anyLong()
        return 0
    }

    private fun revisionOf(number: Int): Long = versions.first { it.versionNumber == number }.revision

    /** Mockito.any devolve null, que o Kotlin recusa em parametro nao nulo. */
    private fun anyUuid(): UUID {
        Mockito.any(UUID::class.java)
        return UUID(0, 0)
    }

    private fun anyReport(): ReportEntity {
        Mockito.any(ReportEntity::class.java)
        return ReportEntity()
    }

    private fun authenticate(ai: Boolean) {
        val authorities = buildList {
            add("REPORT_WRITE")
            add(if (ai) ReportVersionService.AI_AGENT_AUTHORITY else "ROLE_USER")
        }
        val principal = AuthUserPrincipal(
            userId = UUID.randomUUID(),
            name = if (ai) "token:agente" else "Ana",
            email = "ana@example.com",
            passwordHash = "",
            active = true,
            authoritiesList = authorities
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            authorities.map { SimpleGrantedAuthority(it) }
        )
    }
}
