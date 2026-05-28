package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class ReportServiceColumnsTest {

    @Test
    fun `columns should return merged columns and use cache`() {
        val repository = Mockito.mock(ReportRepository::class.java)
        val folderRepository = Mockito.mock(ReportFolderRepository::class.java)
        val jasperTemplateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java)
        val accessControl = Mockito.mock(ReportAccessControlService::class.java)
        val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
        val hanaQueryService = Mockito.mock(HanaQueryService::class.java)

        val reportId = UUID.randomUUID()
        val report = ReportEntity(
            id = reportId,
            name = "R1",
            templateName = "T1",
            sql = "SELECT 1 AS id_agromobi, CURRENT_DATE AS data_entrada",
            secondSql = "SELECT 1 AS id_agromobi, 5 AS custo_total_entrada"
        )

        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(report))
        Mockito.`when`(sqlExecClient.exec(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), eq(true), eq(true))).thenReturn(
            mapOf(
                "description" to listOf(
                    mapOf("name" to "id_agromobi", "type" to "varchar"),
                    mapOf("name" to "data_entrada", "type" to "date")
                ),
                "data" to emptyList<Map<String, Any?>>()
            )
        )
        Mockito.`when`(hanaQueryService.extractColumns(Mockito.anyString(), eq(3))).thenReturn(
            listOf(
                HanaQueryService.ColumnMetadata("id_agromobi", "string"),
                HanaQueryService.ColumnMetadata("custo_total_entrada", "number")
            )
        )

        val service = ReportService(
            repository = repository,
            folderRepository = folderRepository,
            jasperTemplateRepository = jasperTemplateRepository,
            accessControl = accessControl,
            queryService = ReportQueryService(),
            executionService = ReportExecutionService(sqlExecClient, ReportQueryService()),
            variableService = ReportVariableService(),
            columnsService = ReportColumnsService(
                sqlExecClient = sqlExecClient,
                hanaQueryService = hanaQueryService,
                queryService = ReportQueryService(),
                clock = Clock.fixed(Instant.parse("2026-05-27T19:15:00Z"), ZoneOffset.UTC)
            ),
            pdfService = ReportPdfService(),
            mapperService = ReportMapperService(),
            validationService = ReportValidationService(ReportVariableService(), ReportQueryService(), sqlExecClient),
            summaryService = ReportSummaryService(),
            comparisonService = ReportComparisonService(),
            sqlExecClient = sqlExecClient,
            hanaQueryService = hanaQueryService,
            properties = DbInspectorProperties(),
            clock = Clock.fixed(Instant.parse("2026-05-27T19:15:00Z"), ZoneOffset.UTC)
        )

        val ctx = UpstreamContext("https://api.local/sql", "Bearer x")
        val response1 = service.columns(reportId, ctx, source = "both", includeTypes = true, refresh = false)
        val response2 = service.columns(reportId, ctx, source = "both", includeTypes = true, refresh = false)

        assertFalse(response1.cache.hit)
        assertTrue(response2.cache.hit)
        assertEquals(listOf("id_agromobi", "data_entrada", "custo_total_entrada"), response1.mergedColumns)
        assertEquals("date", response1.sources["primary"]!!.columns[1].type)
        Mockito.verify(sqlExecClient, Mockito.times(1)).exec(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), eq(true), eq(true))
        Mockito.verify(hanaQueryService, Mockito.times(1)).extractColumns(Mockito.anyString(), eq(3))
    }

    @Test
    fun `columns should fail with bad request when secondary has no second sql`() {
        val repository = Mockito.mock(ReportRepository::class.java)
        val folderRepository = Mockito.mock(ReportFolderRepository::class.java)
        val jasperTemplateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java)
        val accessControl = Mockito.mock(ReportAccessControlService::class.java)
        val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
        val hanaQueryService = Mockito.mock(HanaQueryService::class.java)

        val reportId = UUID.randomUUID()
        val report = ReportEntity(
            id = reportId,
            name = "R2",
            templateName = "T2",
            sql = "SELECT 1",
            secondSql = null
        )
        Mockito.`when`(repository.findById(reportId)).thenReturn(Optional.of(report))

        val service = ReportService(
            repository = repository,
            folderRepository = folderRepository,
            jasperTemplateRepository = jasperTemplateRepository,
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
            sqlExecClient = sqlExecClient,
            hanaQueryService = hanaQueryService,
            properties = DbInspectorProperties()
        )

        val ex = assertThrows(ResponseStatusException::class.java) {
            service.columns(reportId, UpstreamContext("https://api.local/sql", "Bearer x"), source = "secondary")
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
