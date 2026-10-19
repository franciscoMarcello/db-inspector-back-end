package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseActions
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException

class KeycloakAdminClientTest {

    private val properties = DbInspectorProperties().apply {
        keycloak.url = "https://sso.exemplo.com.br"
        keycloak.realm = "rovema"
        keycloak.clientId = "agroreports"
        keycloak.admin.enabled = true
        keycloak.admin.clientId = "agroreports-admin"
        keycloak.admin.clientSecret = "segredo"
    }
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = KeycloakAdminClient(properties, builder.build(), ObjectMapper())

    @Test
    fun `cria a role do perfil no client de login com a marca do agroreports`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles")
            .andExpect(header("Authorization", "Bearer adm-1"))
            .andExpect(jsonPath("$.name").value("ANALISTA"))
            .andExpect(jsonPath("$.description").value(KeycloakAdminClient.ROLE_MARK))
            .andRespond(withStatus(HttpStatus.CREATED))

        assertEquals(RoleEnsureResult.CREATED, client.ensureRole("analista"))
        server.verify()
    }

    @Test
    fun `role ja criada pelo agroreports nao e erro`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withStatus(HttpStatus.CONFLICT))
        expectRole("ANALISTA")

        assertEquals(RoleEnsureResult.EXISTING_MANAGED, client.ensureRole("ANALISTA"))
    }

    @Test
    fun `role de mesmo nome criada fora do agroreports e recusada ao criar perfil`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withStatus(HttpStatus.CONFLICT))
        expectRole("FINANCEIRO", description = "role de outro sistema")

        val error = assertThrows(KeycloakAdminException::class.java) { client.ensureRole("FINANCEIRO") }

        assertTrue(error.message.orEmpty().contains("nao foi criada pelo agroreports"))
    }

    @Test
    fun `conferencia do boot aceita role criada a mao e informa`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withStatus(HttpStatus.CONFLICT))
        expectRole("ADMIN", description = "")

        assertEquals(RoleEnsureResult.EXISTING_UNMANAGED, client.ensureRole("ADMIN", allowUnmanaged = true))
    }

    @Test
    fun `nao exclui role criada a mao no console`() {
        expectToken()
        expectClientLookup()
        expectRole("ADMIN", description = "criada a mao")

        val error = assertThrows(KeycloakAdminException::class.java) { client.removeRole("ADMIN") }

        assertTrue(error.message.orEmpty().contains("nao foi criada pelo agroreports"))
        server.verify()
    }

    @Test
    fun `exclui a role marcada pelo id que acabou de ler`() {
        expectToken()
        expectClientLookup()
        expectRole("ANALISTA")
        expect(HttpMethod.DELETE, "/roles-by-id/$ROLE_ID").andRespond(withNoContent())

        assertTrue(client.removeRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `excluir role que nao existe no Keycloak nao e erro`() {
        expectToken()
        expectClientLookup()
        // Id do client recem-buscado: o 404 e da role, sem nova busca do client.
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/ANALISTA").andRespond(withResourceNotFound())

        assertFalse(client.removeRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `renomeia pelo id e mantem quem ja tinha o perfil`() {
        expectToken()
        expectClientLookup()
        expectRole("ANALISTA")
        expect(HttpMethod.PUT, "/roles-by-id/$ROLE_ID")
            .andExpect(jsonPath("$.name").value("ANALISTA_FISCAL"))
            .andExpect(jsonPath("$.description").value(KeycloakAdminClient.ROLE_MARK))
            .andRespond(withNoContent())

        client.renameRole("ANALISTA", "ANALISTA_FISCAL")
        server.verify()
    }

    @Test
    fun `client recriado no Keycloak busca o id de novo uma vez`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withStatus(HttpStatus.CREATED))
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withResourceNotFound())
        expectClientLookup(id = "client-uuid-2")
        expect(HttpMethod.POST, "/clients/client-uuid-2/roles").andRespond(withStatus(HttpStatus.CREATED))

        client.ensureRole("ANALISTA")
        assertEquals(RoleEnsureResult.CREATED, client.ensureRole("GESTOR"))
        server.verify()
    }

    @Test
    fun `token de administracao revogado e renovado uma vez`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withUnauthorizedRequest())
        expectToken(value = "adm-2")
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles")
            .andExpect(header("Authorization", "Bearer adm-2"))
            .andRespond(withStatus(HttpStatus.CREATED))

        assertEquals(RoleEnsureResult.CREATED, client.ensureRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `criacao sem resposta mas aplicada no Keycloak conta como criada`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond { throw SocketTimeoutException("Read timed out") }
        expectRole("ANALISTA")

        assertEquals(RoleEnsureResult.CREATED, client.ensureRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `exclusao sem resposta e com a role ainda la continua sendo erro`() {
        expectToken()
        expectClientLookup()
        expectRole("ANALISTA")
        expect(HttpMethod.DELETE, "/roles-by-id/$ROLE_ID").andRespond { throw SocketTimeoutException("Read timed out") }
        expectRole("ANALISTA")

        val error = assertThrows(KeycloakAdminException::class.java) { client.removeRole("ANALISTA") }

        assertTrue(error.uncertain)
        server.verify()
    }

    @Test
    fun `renomeacao sem resposta mas aplicada no Keycloak conta como feita`() {
        expectToken()
        expectClientLookup()
        expectRole("ANALISTA")
        expect(HttpMethod.PUT, "/roles-by-id/$ROLE_ID").andRespond { throw SocketTimeoutException("Read timed out") }
        expectRole("ANALISTA_FISCAL")
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/ANALISTA").andRespond(withResourceNotFound())
        // 404 com o id do client em cache: confere se o client nao foi recriado antes de concluir.
        expectClientLookup()

        client.renameRole("ANALISTA", "ANALISTA_FISCAL")
        server.verify()
    }

    @Test
    fun `lista as roles do client dizendo quais foram criadas pelo agroreports`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles?briefRepresentation=false&first=0&max=500")
            .andRespond(
                withSuccess(
                    """[{"name":"ANALISTA","description":"${KeycloakAdminClient.ROLE_MARK}"},{"name":"admin"}]""",
                    MediaType.APPLICATION_JSON
                )
            )

        assertEquals(mapOf("ANALISTA" to true, "ADMIN" to false), client.listRoles())
    }

    @Test
    fun `lista as roles de todas as paginas`() {
        expectToken()
        expectClientLookup()
        val fullPage = (1..500).joinToString(",", "[", "]") { """{"name":"PERFIL_$it"}""" }
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles?briefRepresentation=false&first=0&max=500")
            .andRespond(withSuccess(fullPage, MediaType.APPLICATION_JSON))
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles?briefRepresentation=false&first=500&max=500")
            .andRespond(withSuccess("""[{"name":"ULTIMO"}]""", MediaType.APPLICATION_JSON))

        val roles = client.listRoles()

        assertEquals(501, roles.size)
        assertTrue("ULTIMO" in roles)
        server.verify()
    }

    @Test
    fun `perfil que ja tem role e conferido com uma leitura so`() {
        expectToken()
        expectClientLookup()
        expectRole("ANALISTA")

        assertEquals(RoleEnsureResult.EXISTING_MANAGED, client.ensureExistingRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `perfil sem role ganha a role ao ser salvo`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/ANALISTA").andRespond(withResourceNotFound())
        expect(HttpMethod.POST, "/clients/$CLIENT_ID/roles").andRespond(withStatus(HttpStatus.CREATED))

        assertEquals(RoleEnsureResult.CREATED, client.ensureExistingRole("ANALISTA"))
        server.verify()
    }

    @Test
    fun `nome fora do padrao nao chega ao Keycloak`() {
        assertThrows(KeycloakAdminException::class.java) { client.ensureRole("GESTOR COMPRAS") }
        assertThrows(KeycloakAdminException::class.java) { client.ensureRole("../ADMIN") }
        server.verify()
    }

    @Test
    fun `le se a conta esta ativa e os perfis efetivos da pessoa`() {
        expectToken()
        expect(HttpMethod.GET, "/users/$USER_ID")
            .andRespond(withSuccess("""{"id":"$USER_ID","enabled":true}""", MediaType.APPLICATION_JSON))
        expectClientLookup()
        expect(HttpMethod.GET, "/users/$USER_ID/role-mappings/clients/$CLIENT_ID/composite")
            .andRespond(withSuccess("""[{"name":"analista"},{"name":"ADMIN"}]""", MediaType.APPLICATION_JSON))

        val access = client.userAccess(USER_ID)

        assertEquals(KeycloakUserAccess(enabled = true, roles = setOf("ANALISTA", "ADMIN")), access)
    }

    @Test
    fun `pessoa apagada no Keycloak volta nula`() {
        expectToken()
        expect(HttpMethod.GET, "/users/$USER_ID").andRespond(withResourceNotFound())

        assertNull(client.userAccess(USER_ID))
    }

    @Test
    fun `role atribuida so por grupo conta como em uso`() {
        expectToken()
        expectClientLookup()
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/ANALISTA/users?first=0&max=1")
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/ANALISTA/groups?first=0&max=1")
            .andRespond(withSuccess("""[{"id":"grupo-1"}]""", MediaType.APPLICATION_JSON))

        assertTrue(client.roleInUse("ANALISTA"))
    }

    private fun expectToken(value: String = "adm-1") {
        server.expect(requestTo("https://sso.exemplo.com.br/realms/rovema/protocol/openid-connect/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(mapOf("grant_type" to "client_credentials", "client_id" to "agroreports-admin")))
            .andRespond(withSuccess("""{"access_token":"$value","expires_in":300}""", MediaType.APPLICATION_JSON))
    }

    private fun expectClientLookup(id: String = CLIENT_ID) {
        expect(HttpMethod.GET, "/clients?clientId=agroreports")
            .andRespond(withSuccess("""[{"id":"$id","clientId":"agroreports"}]""", MediaType.APPLICATION_JSON))
    }

    private fun expectRole(name: String, description: String = KeycloakAdminClient.ROLE_MARK) {
        expect(HttpMethod.GET, "/clients/$CLIENT_ID/roles/$name")
            .andRespond(
                withSuccess("""{"id":"$ROLE_ID","name":"$name","description":"$description"}""", MediaType.APPLICATION_JSON)
            )
    }

    private fun expect(httpMethod: HttpMethod, path: String): ResponseActions =
        server.expect(requestTo("$ADMIN$path")).andExpect(method(httpMethod))

    private companion object {
        const val ADMIN = "https://sso.exemplo.com.br/admin/realms/rovema"
        const val CLIENT_ID = "client-uuid-1"
        const val ROLE_ID = "role-uuid-1"
        const val USER_ID = "user-uuid-ana"
    }
}
