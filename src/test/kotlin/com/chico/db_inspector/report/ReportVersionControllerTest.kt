package com.chico.dbinspector.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class ReportVersionControllerTest {

    private val versionService: ReportVersionService = Mockito.mock(ReportVersionService::class.java)
    private val reportService: ReportService = Mockito.mock(ReportService::class.java)
    private val mapper = jacksonObjectMapper()
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ReportVersionController(versionService, reportService))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    private val reportId = UUID.randomUUID()

    @Test
    fun `lista o historico de versoes do relatorio`() {
        Mockito.`when`(versionService.list(reportId)).thenReturn(
            listOf(summary(2, ReportVersionStatus.DRAFT, "AI_TOKEN"), summary(1, ReportVersionStatus.PUBLISHED, "SYSTEM"))
        )

        mockMvc.perform(get("/api/db/reports/$reportId/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].versionNumber").value(2))
            .andExpect(jsonPath("$[0].status").value("DRAFT"))
            .andExpect(jsonPath("$[0].authorType").value("AI_TOKEN"))
            .andExpect(jsonPath("$[1].status").value("PUBLISHED"))
    }

    @Test
    fun `publicar repassa a nota e devolve a versao publicada`() {
        Mockito.`when`(versionService.publish(reportId, 2, "revisado", 1234L)).thenReturn(version(2, ReportVersionStatus.PUBLISHED))

        mockMvc.perform(
            post("/api/db/reports/$reportId/versions/2/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(ReportVersionActionRequest(note = "revisado", expectedRevision = 1234L)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.jrxml").value("<jasperReport/>"))

        Mockito.verify(versionService).publish(reportId, 2, "revisado", 1234L)
    }

    @Test
    fun `publicar sem corpo funciona`() {
        Mockito.`when`(versionService.publish(reportId, 2, null, null)).thenReturn(version(2, ReportVersionStatus.PUBLISHED))

        mockMvc.perform(post("/api/db/reports/$reportId/versions/2/publish"))
            .andExpect(status().isOk)
    }

    @Test
    fun `publicar com token de IA devolve 403`() {
        Mockito.`when`(versionService.publish(reportId, 2, null, null))
            .thenThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Token de IA nao pode publicar"))

        mockMvc.perform(post("/api/db/reports/$reportId/versions/2/publish"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `descartar informa se o relatorio foi removido`() {
        Mockito.`when`(versionService.discard(reportId, 1)).thenReturn(true)

        mockMvc.perform(post("/api/db/reports/$reportId/versions/1/discard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reportDeleted").value(true))
    }

    @Test
    fun `restaurar cria versao nova a partir da antiga`() {
        Mockito.`when`(versionService.restore(reportId, 1, null)).thenReturn(version(3, ReportVersionStatus.PUBLISHED))

        mockMvc.perform(post("/api/db/reports/$reportId/versions/1/restore"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versionNumber").value(3))
    }

    private fun summary(number: Int, status: ReportVersionStatus, authorType: String) = ReportVersionSummaryResponse(
        versionNumber = number,
        status = status.name,
        name = "Colheita",
        baseVersionNumber = null,
        authorType = authorType,
        authorName = "autor",
        note = null,
        hasJrxml = false,
        createdAt = 0,
        updatedAt = null,
        publishedAt = null,
        publishedBy = null
    )

    private fun version(number: Int, status: ReportVersionStatus) = ReportVersionResponse(
        reportId = reportId.toString(),
        versionNumber = number,
        status = status.name,
        baseVersionNumber = 1,
        name = "Colheita",
        description = null,
        folderId = null,
        sql = "SELECT 1",
        secondSql = null,
        comparisonKey = null,
        variables = emptyList(),
        jasperTemplateId = null,
        jrxml = "<jasperReport/>",
        authorType = "USER",
        authorName = "Ana",
        note = null,
        createdAt = 0,
        updatedAt = null,
        publishedAt = 0,
        publishedBy = "Ana"
    )
}
