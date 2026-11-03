package com.chico.dbinspector.ai

import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

class AiQueryControllerTest {

    private val queryService: AiQueryService = Mockito.mock(AiQueryService::class.java)
    private val approvalService: AiApprovalService = Mockito.mock(AiApprovalService::class.java)
    private val adminApprovalController = AiApprovalAdminController(approvalService)
    private val mapper = jacksonObjectMapper()
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AiQueryController(queryService, approvalService), adminApprovalController)
        .setControllerAdvice(AiExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .setCustomArgumentResolvers(FixedUpstreamContextResolver())
        .build()

    @Test
    fun `consulta devolve colunas e linhas`() {
        val body = AiQueryRequest("SELECT 1 AS a")
        Mockito.`when`(queryService.query(body, CTX)).thenReturn(
            AiQueryResponse(listOf("a"), listOf(mapOf("a" to 1)), 1, false, 200, 1.0, 5, null, null)
        )

        mockMvc.perform(post("/api/db/ai/consultas").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.colunas[0]").value("a"))
            .andExpect(jsonPath("$.linhas[0].a").value(1))
    }

    @Test
    fun `problemas de validacao voltam como 422 legivel por maquina`() {
        val body = AiQueryRequest("SELECT nextval('s')")
        Mockito.`when`(queryService.query(body, CTX)).thenThrow(
            AiValidationException(listOf(AiProblem("sql", "funcao_proibida", "A funcao 'nextval' nao e permitida", 1)))
        )

        mockMvc.perform(post("/api/db/ai/consultas").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.erro").value("validacao_falhou"))
            .andExpect(jsonPath("$.problemas[0].regra").value("funcao_proibida"))
            .andExpect(jsonPath("$.problemas[0].linha").value(1))
    }

    @Test
    fun `limite devolve 429 com dica`() {
        val body = AiQueryRequest("SELECT 1")
        Mockito.`when`(queryService.query(body, CTX)).thenThrow(
            AiRuleException(HttpStatus.TOO_MANY_REQUESTS, "limite_de_frequencia", "Limite atingido", "Espere um minuto")
        )

        mockMvc.perform(post("/api/db/ai/consultas").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.erro").value("limite_de_frequencia"))
            .andExpect(jsonPath("$.dica").value("Espere um minuto"))
    }

    @Test
    fun `SQL vazio e recusado pela validacao do corpo`() {
        mockMvc.perform(post("/api/db/ai/consultas").contentType(MediaType.APPLICATION_JSON).content("""{"sql":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.erro").value("requisicao_invalida"))
    }

    @Test
    fun `pedido de liberacao devolve 201 e pode ser consultado`() {
        val id = UUID.randomUUID()
        val body = AiApprovalRequest("SELECT 1", "preciso de mais linhas", 1_000)
        val response = approval(id, "PENDING")
        Mockito.`when`(approvalService.request(body)).thenReturn(response)
        Mockito.`when`(approvalService.getForToken(id)).thenReturn(response)

        mockMvc.perform(post("/api/db/ai/liberacoes").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
        mockMvc.perform(get("/api/db/ai/liberacoes/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
    }

    @Test
    fun `operador aprova pedido`() {
        val id = UUID.randomUUID()
        val decision = AiApprovalDecisionRequest(linhas = 2_000, minutos = 30)
        Mockito.`when`(approvalService.approve(id, decision)).thenReturn(approval(id, "APPROVED"))

        mockMvc.perform(
            post("/api/db/ai-approvals/$id/approve").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(decision))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
    }

    @Test
    fun `contador de pendentes para o selo da tela`() {
        Mockito.`when`(approvalService.pendingCount()).thenReturn(3)

        mockMvc.perform(get("/api/db/ai-approvals/pending-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pending").value(3))
    }

    private fun approval(id: UUID, status: String) = AiApprovalResponse(
        id = id.toString(), status = status, sql = "SELECT 1", motivo = "x", linhasPedidas = 1_000, linhasAprovadas = null,
        custoEstimado = null, token = "t", solicitadoPor = "ana@example.com", decididoPor = null, decididoEm = null,
        nota = null, expiraEm = null, criadoEm = 0, vigente = false
    )

    private class FixedUpstreamContextResolver : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == UpstreamContext::class.java
        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?
        ): Any = CTX
    }

    private companion object {
        val CTX = UpstreamContext("https://api.example.com/sql/exec/", "Bearer x")
    }
}
