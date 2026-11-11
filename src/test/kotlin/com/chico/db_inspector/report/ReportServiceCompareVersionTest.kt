package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class ReportServiceCompareVersionTest {

    private val repository = Mockito.mock(ReportRepository::class.java)
    private val accessControl = Mockito.mock(ReportAccessControlService::class.java)
    private val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
    private val hanaQueryService = Mockito.mock(HanaQueryService::class.java)
    private val versionService = Mockito.mock(ReportVersionService::class.java)

    private val service = ReportService(
        repository = repository,
        folderRepository = Mockito.mock(ReportFolderRepository::class.java),
        jasperTemplateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java),
        accessControl = accessControl,
        queryService = ReportQueryService(),
        executionService = ReportExecutionService(sqlExecClient, ReportQueryService()),
        variableService = ReportVariableService(),
        columnsService = ReportColumnsService(
            sqlExecClient = sqlExecClient,
            hanaQueryService = hanaQueryService,
            queryService = ReportQueryService()
        ),
        pdfService = ReportPdfService(),
        mapperService = ReportMapperService(),
        validationService = ReportValidationService(ReportVariableService(), ReportQueryService(), sqlExecClient),
        summaryService = ReportSummaryService(),
        comparisonService = ReportComparisonService(),
        versionService = versionService,
        jrxmlGuard = com.chico.dbinspector.ai.AiJrxmlGuard(),
        sqlExecClient = sqlExecClient,
        hanaQueryService = hanaQueryService,
        properties = DbInspectorProperties()
    )

    private val reportId = UUID.randomUUID()
    private val ctx = UpstreamContext("https://api.local/sql", "Bearer x")

    @Test
    fun `deve comparar a versao escolhida com o SAP e nao o SQL do publicado`() {
        val published = publishedReport()
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(published))
        Mockito.`when`(versionService.transientReport(reportId, 8)).thenReturn(
            ReportEntity(
                id = reportId,
                name = "Rascunho v8",
                sql = "SELECT 1 AS id, 10 AS valor",
                secondSql = "SELECT 1 AS id, 12 AS valor",
                comparisonKey = "id"
            )
        )
        Mockito.`when`(sqlExecClient.exec(anyString(), anyString(), anyString(), eq(true), eq(true))).thenReturn(
            mapOf("data" to listOf(mapOf("id" to 1, "valor" to 10)))
        )
        Mockito.`when`(hanaQueryService.exec(anyString())).thenReturn(
            HanaQueryService.QueryResult(listOf("id", "valor"), listOf(mapOf("id" to 1, "valor" to 12)))
        )

        val response = service.compareVersion(reportId, 8, ctx, ReportRunRequest())

        assertEquals("Rascunho v8", response.name)
        assertEquals("id", response.comparisonKey)
        assertEquals(1, response.diff?.differentCount)
        Mockito.verify(hanaQueryService).exec("SELECT 1 AS id, 12 AS valor")
        Mockito.verify(hanaQueryService, Mockito.never()).exec("SELECT 99 AS id FROM DUMMY")
        Mockito.verify(accessControl).requireReportAccess(published, AccessAction.RUN)
    }

    @Test
    fun `deve exigir permissao de executar o relatorio para comparar uma versao`() {
        val published = publishedReport()
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(published))
        Mockito.doThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissao"))
            .`when`(accessControl).requireReportAccess(published, AccessAction.RUN)

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.compareVersion(reportId, 8, ctx, ReportRunRequest())
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        Mockito.verify(versionService, Mockito.never()).transientReport(reportId, 8)
        Mockito.verify(hanaQueryService, Mockito.never()).exec(anyString())
    }

    @Test
    fun `executar uma versao exige permissao de executar o relatorio`() {
        val published = publishedReport()
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(published))
        Mockito.doThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissao"))
            .`when`(accessControl).requireReportAccess(published, AccessAction.RUN)

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.runVersion(reportId, 8, ctx, ReportRunRequest())
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        Mockito.verify(versionService, Mockito.never()).transientReport(reportId, 8)
        Mockito.verify(sqlExecClient, Mockito.never()).exec(anyString(), anyString(), anyString(), eq(true), eq(true))
    }

    @Test
    fun `PDF de uma versao exige permissao de executar o relatorio`() {
        val published = publishedReport()
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(published))
        Mockito.doThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Sem permissao"))
            .`when`(accessControl).requireReportAccess(published, AccessAction.RUN)

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.generateVersionPdf(reportId, 8, ctx, ReportRunRequest())
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        Mockito.verify(versionService, Mockito.never()).get(reportId, 8)
        Mockito.verify(versionService, Mockito.never()).transientReport(reportId, 8)
    }

    private fun publishedReport() = ReportEntity(
        id = reportId,
        name = "Publicado",
        sql = "SELECT 99 AS id",
        secondSql = "SELECT 99 AS id FROM DUMMY",
        comparisonKey = "id"
    )

    @Test
    fun `deve recusar comparar versao sem SQL do SAP`() {
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(publishedReport()))
        Mockito.`when`(versionService.transientReport(reportId, 3)).thenReturn(
            ReportEntity(id = reportId, name = "So SQL", sql = "SELECT 1 AS id")
        )

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.compareVersion(reportId, 3, ctx, ReportRunRequest())
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        Mockito.verify(hanaQueryService, Mockito.never()).exec(anyString())
    }

    @Test
    fun `comparar o relatorio publicado continua lendo o publicado e checando a permissao de executar`() {
        val published = ReportEntity(
            id = reportId,
            name = "Publicado",
            sql = "SELECT 1 AS id, 5 AS valor",
            secondSql = "SELECT 1 AS id, 5 AS valor",
            comparisonKey = "id"
        )
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(published))
        Mockito.`when`(sqlExecClient.exec(anyString(), anyString(), anyString(), eq(true), eq(true))).thenReturn(
            mapOf("data" to listOf(mapOf("id" to 1, "valor" to 5)))
        )
        Mockito.`when`(hanaQueryService.exec(anyString())).thenReturn(
            HanaQueryService.QueryResult(listOf("id", "valor"), listOf(mapOf("id" to 1, "valor" to 5)))
        )

        val response = service.compare(reportId, ctx, ReportRunRequest())

        assertEquals(1, response.diff?.equalCount)
        Mockito.verify(accessControl).requireReportAccess(published, AccessAction.RUN)
        Mockito.verify(versionService, Mockito.never()).transientReport(reportId, 1)
    }
}
