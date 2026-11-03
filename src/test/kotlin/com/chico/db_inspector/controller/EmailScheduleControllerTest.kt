package com.chico.dbinspector.controller

import com.chico.dbinspector.email.ComparisonGroupSummary
import com.chico.dbinspector.email.ComparisonPreviewRequest
import com.chico.dbinspector.email.ComparisonPreviewResponse
import com.chico.dbinspector.email.EmailReportScheduler
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.math.BigDecimal

class EmailScheduleControllerTest {

    private val scheduler: EmailReportScheduler = Mockito.mock(EmailReportScheduler::class.java)
    private val mapper = jacksonObjectMapper()
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(EmailScheduleController(scheduler))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .setCustomArgumentResolvers(FixedUpstreamContextResolver(CTX))
        .build()

    @Test
    fun `preview deve devolver contadores e colunas sem enviar email`() {
        val body = ComparisonPreviewRequest(sql = "select 1", secondSql = "select 1 from dummy")
        Mockito.`when`(scheduler.previewComparison(body, CTX)).thenReturn(previewResponse())

        mockMvc.perform(
            post("/api/db/email/schedules/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DIVERGENCIA"))
            .andExpect(jsonPath("$.onlyInSource1").value(46))
            .andExpect(jsonPath("$.matchedRows").value(812))
            .andExpect(jsonPath("$.comparedColumns[0]").value("Chave"))
            .andExpect(jsonPath("$.groups[0].group").value("agricola.operacao"))

        Mockito.verify(scheduler).previewComparison(body, CTX)
    }

    @Test
    fun `run deve disparar o agendamento e devolver 202`() {
        mockMvc.perform(post("/api/db/email/schedules/abc-123/run"))
            .andExpect(status().isAccepted)

        Mockito.verify(scheduler).runNow("abc-123")
    }

    private fun previewResponse() = ComparisonPreviewResponse(
        status = "DIVERGENCIA",
        onlyInSource1 = 46,
        onlyInSource2 = 0,
        differentRows = 0,
        matchedRows = 812,
        divergencePercent = 5.36,
        columnsSource1 = listOf("Chave", "Custo Total"),
        columnsSource2 = listOf("Chave", "Custo Total"),
        comparedColumns = listOf("Chave", "Custo Total"),
        groups = listOf(
            ComparisonGroupSummary(
                group = "agricola.operacao",
                onlyInSource1 = 46,
                onlyInSource2 = 0,
                divergentValues = 0,
                deltaSum = BigDecimal.ZERO,
                valueOnlyInSource1 = BigDecimal("5120.39"),
                valueOnlyInSource2 = BigDecimal.ZERO
            )
        ),
        differencesSample = emptyList(),
        differencesTotal = 46
    )

    /** Em producao o UpstreamContext vem do UpstreamContextResolver; aqui basta um valor fixo. */
    private class FixedUpstreamContextResolver(private val ctx: UpstreamContext) : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter): Boolean =
            parameter.parameterType == UpstreamContext::class.java

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?
        ): Any = ctx
    }

    private companion object {
        val CTX = UpstreamContext(endpointUrl = "https://api.example.com/sql/exec/", bearer = "Bearer token")
    }
}
