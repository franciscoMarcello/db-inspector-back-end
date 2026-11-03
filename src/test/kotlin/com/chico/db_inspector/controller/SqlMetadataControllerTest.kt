package com.chico.dbinspector.controller

import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.MethodParameter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class SqlMetadataControllerTest {

    private val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(SqlMetadataController(sqlExecClient))
        .setCustomArgumentResolvers(FixedUpstreamContextResolver())
        .build()

    @Test
    fun `deve recusar injecao de SQL no nome do schema`() {
        mockMvc.perform(get("/api/db/{schema}/tables", "agro' UNION SELECT usename FROM pg_user --"))
            .andExpect(status().isBadRequest)

        Mockito.verifyNoInteractions(sqlExecClient)
    }

    @Test
    fun `deve recusar injecao de SQL no nome da tabela em detalhes e relacoes`() {
        mockMvc.perform(get("/api/db/{schema}/{table}/details", "agro", "fazenda' OR '1'='1"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/db/{schema}/{table}/relations", "agro", "fazenda'); DROP TABLE x; --"))
            .andExpect(status().isBadRequest)

        Mockito.verifyNoInteractions(sqlExecClient)
    }

    @Test
    fun `deve aceitar identificador comum`() {
        Mockito.`when`(sqlExecClient.exec(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.eq(true), Mockito.eq(true)))
            .thenReturn(mapOf("data" to emptyList<Any>()))

        mockMvc.perform(get("/api/db/{schema}/tables", "agricola"))
            .andExpect(status().isOk)

        Mockito.verify(sqlExecClient).exec(
            Mockito.anyString(), Mockito.anyString(), Mockito.contains("table_schema = 'agricola'") ?: "", Mockito.eq(true), Mockito.eq(true)
        )
    }

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
