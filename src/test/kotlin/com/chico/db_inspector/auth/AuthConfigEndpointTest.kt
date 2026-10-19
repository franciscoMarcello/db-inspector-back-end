package com.chico.dbinspector.auth

import com.chico.dbinspector.DbInspectorApplication
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [DbInspectorApplication::class])
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "dbinspector.keycloak.enabled=true",
        "dbinspector.keycloak.url=https://sso.exemplo.com.br/",
        "dbinspector.keycloak.realm=rovema",
        "dbinspector.keycloak.client-id=agroreports",
        // Desligada: o teste nao pode depender do application.properties local nem falar com um Keycloak.
        "dbinspector.keycloak.admin.enabled=false",
        "dbinspector.keycloak.admin.client-id=agroreports-admin",
        "dbinspector.keycloak.admin.client-secret=segredo-do-teste"
    ]
)
class AuthConfigEndpointTest(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `config e publica e diz ao front como entrar sem expor o segredo`() {
        mockMvc.perform(get("/api/auth/config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.localLoginEnabled").value(false))
            .andExpect(jsonPath("$.keycloak.url").value("https://sso.exemplo.com.br"))
            .andExpect(jsonPath("$.keycloak.realm").value("rovema"))
            .andExpect(jsonPath("$.keycloak.clientId").value("agroreports"))
            .andExpect(content().string(not(containsString("segredo-do-teste"))))
            .andExpect(content().string(not(containsString("agroreports-admin"))))
    }

    @Test
    fun `rotas autenticadas continuam fechadas sem token`() {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token local e recusado com o Keycloak ligado`() {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.x"))
            .andExpect(status().isUnauthorized)
    }
}
