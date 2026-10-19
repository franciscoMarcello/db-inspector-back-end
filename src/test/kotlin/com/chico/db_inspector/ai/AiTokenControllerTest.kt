package com.chico.dbinspector.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class AiTokenControllerTest {

    private val service: AiTokenService = Mockito.mock(AiTokenService::class.java)
    private val mapper = jacksonObjectMapper()
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AiTokenController(service))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun `criar devolve 201 com o valor do token`() {
        val body = AiTokenCreateRequest(name = "claude-code", validityDays = 30)
        Mockito.`when`(service.create(body)).thenReturn(
            AiTokenCreatedResponse("id-1", "claude-code", "rpt_valor", 1L, "Guarde agora")
        )

        mockMvc.perform(
            post("/api/db/ai/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").value("rpt_valor"))
    }

    @Test
    fun `listar nao expoe o valor do token`() {
        Mockito.`when`(service.list()).thenReturn(
            listOf(AiTokenResponse("id-1", "claude-code", "rpt_abcdefgh", "ana@example.com", 0, 1, null, null, true, false))
        )

        mockMvc.perform(get("/api/db/ai/tokens"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].prefix").value("rpt_abcdefgh"))
            .andExpect(jsonPath("$[0].token").doesNotExist())
    }

    @Test
    fun `revogar devolve 204`() {
        val id = UUID.randomUUID()

        mockMvc.perform(delete("/api/db/ai/tokens/$id"))
            .andExpect(status().isNoContent)

        Mockito.verify(service).revoke(id)
    }
}
