package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.Clock
import java.time.Instant

/** [uncertain]: a requisicao pode ter chegado ao Keycloak (rede ou timeout sem resposta). */
class KeycloakAdminException(
    message: String,
    cause: Throwable? = null,
    val uncertain: Boolean = false
) : RuntimeException(message, cause)

enum class RoleEnsureResult {
    CREATED,
    EXISTING_MANAGED,

    /** Ja existia sem a marca do agroreports: criada a mao no console (como a ADMIN) ou de outro uso. */
    EXISTING_UNMANAGED
}

/** O que o Keycloak diz de uma pessoa agora: se a conta esta ativa e os perfis efetivos (inclusive por grupo). */
data class KeycloakUserAccess(
    val enabled: Boolean,
    val roles: Set<String>
)

/**
 * API de administracao do Keycloak. Os perfis do agroreports sao roles do client de login
 * ([DbInspectorProperties.KeycloakProperties.clientId]); a atribuicao de perfil a pessoas e feita no console.
 *
 * Toda role criada aqui leva [ROLE_MARK] na descricao, e so role com essa marca e renomeada ou excluida
 * por aqui: uma role criada a mao no console (como a ADMIN) nunca some por um clique na tela.
 *
 * Usa um client confidencial com service account (client_credentials); o token fica em memoria ate
 * perto de expirar.
 */
@Service
@ConditionalOnProperty(value = ["dbinspector.keycloak.admin.enabled"], havingValue = "true")
class KeycloakAdminClient(
    private val properties: DbInspectorProperties,
    @Qualifier("keycloakRestClient") private val http: RestClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val ROLE_MARK = "Perfil criado pelo agroreports"

        /** Nada que mude o caminho da URL. Perfis do agroreports sao sempre maiusculos. */
        private val SAFE_ROLE = Regex("^[A-Z0-9][A-Z0-9_-]{0,63}$")
        private val SAFE_ID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun isValidRoleName(name: String): Boolean = name.matches(SAFE_ROLE)

        private const val ROLE_PAGE_SIZE = 500
    }

    private val log = LoggerFactory.getLogger(KeycloakAdminClient::class.java)
    private val keycloak get() = properties.keycloak

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenValidUntil: Instant = Instant.EPOCH

    @Volatile
    private var clientUuid: String? = null

    init {
        require(keycloak.url.isNotBlank() && keycloak.realm.isNotBlank() && keycloak.clientId.isNotBlank()) {
            "dbinspector.keycloak.url, realm e client-id sao obrigatorios com dbinspector.keycloak.admin.enabled=true"
        }
        require(keycloak.admin.clientId.isNotBlank() && keycloak.admin.clientSecret.isNotBlank()) {
            "dbinspector.keycloak.admin.client-id e client-secret sao obrigatorios com dbinspector.keycloak.admin.enabled=true"
        }
    }

    /**
     * Garante a role do perfil. Uma role de mesmo nome que ja existia sem a marca e de outro uso: quem a tem
     * passaria a receber as permissoes do perfil sem atribuicao nova, por isso so e aceita com
     * [allowUnmanaged] (a conferencia do boot, onde a ADMIN criada a mao e esperada).
     */
    fun ensureRole(name: String, allowUnmanaged: Boolean = false): RoleEnsureResult {
        val role = safeRoleName(name)
        val reply = orReadBack({ clientCall(HttpMethod.POST, { listOf("clients", it, "roles") }, body = roleBody(role)) }) {
            if (readRole(role)?.isMarked() == true) Reply(201, null) else null
        }
        return when (reply.status) {
            201 -> RoleEnsureResult.CREATED.also {
                log.info("Role '{}' criada no client {} do Keycloak", role, keycloak.clientId)
            }
            409 -> {
                val existing = readRole(role) ?: throw KeycloakAdminException("O Keycloak recusou criar a role '$role' (HTTP 409)")
                when {
                    existing.isMarked() -> RoleEnsureResult.EXISTING_MANAGED
                    allowUnmanaged -> RoleEnsureResult.EXISTING_UNMANAGED
                    else -> throw KeycloakAdminException(
                        "Ja existe no Keycloak uma role '$role' que nao foi criada pelo agroreports: quem a tem receberia " +
                            "as permissoes deste perfil. Use outro nome ou resolva a role no console do Keycloak"
                    )
                }
            }
            else -> throw KeycloakAdminException("O Keycloak recusou criar a role '$role' (HTTP ${reply.status})")
        }
    }

    /** true = excluida; false = nao existia. */
    fun removeRole(name: String): Boolean {
        val role = safeRoleName(name)
        val current = readRole(role) ?: return false
        requireMarked(role, current)
        // Exclui pelo id da role que acabou de ser lida: se ela foi trocada por outra de mesmo nome nesse
        // intervalo, o DELETE nao a atinge.
        val status = orReadBack({ call(HttpMethod.DELETE, listOf("roles-by-id", roleId(role, current))).status }) {
            if (readRole(role) == null) 204 else null
        }
        return when (status) {
            204 -> true.also { log.info("Role '{}' excluida do client {} do Keycloak", role, keycloak.clientId) }
            404 -> false
            else -> throw KeycloakAdminException("O Keycloak recusou excluir a role '$role' (HTTP $status)")
        }
    }

    /** Renomeia mantendo o id, entao quem ja tinha o perfil continua com ele. */
    fun renameRole(currentName: String, newName: String) {
        val from = safeRoleName(currentName)
        val to = safeRoleName(newName)
        if (from == to) return
        val current = readRole(from)
        if (current == null) {
            // Role de origem ausente (perfil anterior a integracao, ou renomeacao repetida apos timeout).
            ensureRole(to)
            return
        }
        requireMarked(from, current)
        val status = orReadBack({ call(HttpMethod.PUT, listOf("roles-by-id", roleId(from, current)), body = roleBody(to)).status }) {
            if (readRole(to)?.isMarked() == true && readRole(from) == null) 204 else null
        }
        when (status) {
            204 -> log.info("Role '{}' renomeada para '{}' no client {} do Keycloak", from, to, keycloak.clientId)
            409 -> throw KeycloakAdminException("Ja existe no Keycloak uma role '$to'")
            else -> throw KeycloakAdminException("O Keycloak recusou renomear a role '$from' (HTTP $status)")
        }
    }

    /** Roles do client de login pelo nome em maiusculas: true = criada pelo agroreports (tem a marca). */
    fun listRoles(): Map<String, Boolean> {
        val roles = mutableMapOf<String, Boolean>()
        var first = 0
        while (true) {
            val page = first
            val reply = clientCall(
                HttpMethod.GET,
                { listOf("clients", it, "roles") },
                mapOf("briefRepresentation" to "false", "first" to page.toString(), "max" to ROLE_PAGE_SIZE.toString())
            )
            if (reply.status != 200) {
                throw KeycloakAdminException("O Keycloak nao listou as roles do client '${keycloak.clientId}' (HTTP ${reply.status})")
            }
            val nodes = reply.body?.toList().orEmpty()
            nodes.forEach { node -> node["name"]?.asText()?.uppercase()?.let { roles[it] = node.isMarked() } }
            if (nodes.size < ROLE_PAGE_SIZE) return roles
            first += ROLE_PAGE_SIZE
        }
    }

    /**
     * Garante a role de um perfil que ja existe aqui com uma leitura so no caso comum (a role ja esta la);
     * so cria se faltar. Role sem a marca e aceita: o perfil ja existia e o client e dedicado.
     */
    fun ensureExistingRole(name: String): RoleEnsureResult {
        val role = safeRoleName(name)
        val current = readRole(role) ?: return ensureRole(role, allowUnmanaged = true)
        return if (current.isMarked()) RoleEnsureResult.EXISTING_MANAGED else RoleEnsureResult.EXISTING_UNMANAGED
    }

    /** Alguem recebe a role, direto ou por grupo? */
    fun roleInUse(name: String): Boolean {
        val role = safeRoleName(name)
        val firstOnly = mapOf("first" to "0", "max" to "1")
        val users = clientCall(HttpMethod.GET, { listOf("clients", it, "roles", role, "users") }, firstOnly)
        if (users.status == 404) return false
        val groups = clientCall(HttpMethod.GET, { listOf("clients", it, "roles", role, "groups") }, firstOnly)
        if (users.status != 200 || groups.status != 200) {
            throw KeycloakAdminException(
                "Nao foi possivel conferir no Keycloak quem tem a role '$role' (HTTP ${users.status}/${groups.status})"
            )
        }
        return (users.body?.size() ?: 0) > 0 || (groups.body?.size() ?: 0) > 0
    }

    /** null = a pessoa nao existe mais no Keycloak. */
    fun userAccess(keycloakId: String): KeycloakUserAccess? {
        require(keycloakId.matches(SAFE_ID)) { "Id de usuario do Keycloak invalido" }
        val user = call(HttpMethod.GET, listOf("users", keycloakId))
        if (user.status == 404) return null
        if (user.status != 200) throw KeycloakAdminException("O Keycloak nao leu o usuario (HTTP ${user.status})")
        val mappings = clientCall(
            HttpMethod.GET,
            { listOf("users", keycloakId, "role-mappings", "clients", it, "composite") }
        )
        if (mappings.status != 200) {
            throw KeycloakAdminException("O Keycloak nao leu os perfis do usuario (HTTP ${mappings.status})")
        }
        return KeycloakUserAccess(
            enabled = user.body?.get("enabled")?.asBoolean() == true,
            roles = mappings.body?.mapNotNull { it["name"]?.asText()?.uppercase() }?.toSet() ?: emptySet()
        )
    }

    private fun roleBody(name: String) = mapOf("name" to name, "description" to ROLE_MARK)

    private fun safeRoleName(name: String): String {
        val normalized = name.trim().uppercase()
        if (!isValidRoleName(normalized)) {
            throw KeycloakAdminException("Nome de perfil invalido para o Keycloak: '$normalized'. Use letras, numeros, _ ou -")
        }
        return normalized
    }

    private fun readRole(role: String): JsonNode? {
        val reply = clientCall(HttpMethod.GET, { listOf("clients", it, "roles", role) })
        return when (reply.status) {
            200 -> reply.body ?: throw KeycloakAdminException("O Keycloak devolveu a role '$role' sem conteudo")
            404 -> null
            else -> throw KeycloakAdminException("O Keycloak nao leu a role '$role' (HTTP ${reply.status})")
        }
    }

    private fun JsonNode.isMarked(): Boolean = this["description"]?.asText() == ROLE_MARK

    /**
     * Alteracao sem resposta (timeout, conexao caida) pode ter sido aplicada. Em vez de devolver erro com o
     * Keycloak ja alterado, le de volta: [readBack] devolve o resultado se a alteracao esta la, ou null.
     */
    private fun <T> orReadBack(action: () -> T, readBack: () -> T?): T =
        try {
            action()
        } catch (e: KeycloakAdminException) {
            if (!e.uncertain) throw e
            runCatching(readBack).getOrNull() ?: throw e
        }

    private fun requireMarked(role: String, node: JsonNode) {
        if (!node.isMarked()) {
            throw KeycloakAdminException(
                "A role '$role' do Keycloak nao foi criada pelo agroreports e por isso nao e alterada por aqui: " +
                    "ajuste-a no console do Keycloak"
            )
        }
    }

    private fun roleId(role: String, node: JsonNode): String =
        node["id"]?.asText()?.takeIf { it.matches(SAFE_ID) }
            ?: throw KeycloakAdminException("O Keycloak nao devolveu o id da role '$role'")

    private fun clientUuid(): String {
        clientUuid?.let { return it }
        val reply = call(HttpMethod.GET, listOf("clients"), mapOf("clientId" to keycloak.clientId))
        if (reply.status != 200) {
            throw KeycloakAdminException("O Keycloak nao listou o client '${keycloak.clientId}' (HTTP ${reply.status})")
        }
        val id = reply.body?.firstOrNull()?.get("id")?.asText()?.takeIf { it.matches(SAFE_ID) }
            ?: throw KeycloakAdminException("O client '${keycloak.clientId}' nao existe no realm '${keycloak.realm}'")
        return id.also { clientUuid = it }
    }

    /**
     * Chamada num caminho que leva o id do client. 404 com o id guardado pode ser o client recriado com o
     * mesmo clientId (id novo): busca o id de novo, uma vez, e so repete se ele mudou.
     */
    private fun clientCall(
        method: HttpMethod,
        path: (String) -> List<String>,
        query: Map<String, String> = emptyMap(),
        body: Any? = null
    ): Reply {
        val cached = clientUuid
        val uuid = clientUuid()
        val reply = call(method, path(uuid), query, body)
        if (reply.status != 404 || cached == null) return reply
        clientUuid = null
        val fresh = clientUuid()
        return if (fresh == uuid) reply else call(method, path(fresh), query, body)
    }

    /** Uma tentativa com o token guardado; 401 (token revogado) renova e repete uma vez. */
    private fun call(
        method: HttpMethod,
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        body: Any? = null
    ): Reply {
        val first = send(method, segments, query, body, accessToken())
        if (first.status != 401) return first
        token = null
        return send(method, segments, query, body, accessToken())
    }

    private fun send(
        method: HttpMethod,
        segments: List<String>,
        query: Map<String, String>,
        body: Any?,
        bearer: String
    ): Reply {
        val uri = UriComponentsBuilder.fromUriString(keycloak.url.trimEnd('/'))
            .pathSegment("admin", "realms", keycloak.realm, *segments.toTypedArray())
            .apply { query.forEach { (name, value) -> queryParam(name, value) } }
            .build()
            .encode()
            .toUri()
        val spec = http.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body)
        return execute(spec)
    }

    @Synchronized
    private fun accessToken(): String {
        val current = token
        if (current != null && Instant.now(clock).isBefore(tokenValidUntil)) return current
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", keycloak.admin.clientId)
            add("client_secret", keycloak.admin.clientSecret)
        }
        val reply = execute(
            http.post()
                .uri("${keycloak.issuer()}/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
        )
        if (reply.status != 200) {
            throw KeycloakAdminException(
                "O Keycloak recusou o login do client de administracao '${keycloak.admin.clientId}' (HTTP ${reply.status})"
            )
        }
        val value = reply.body?.get("access_token")?.asText()
            ?: throw KeycloakAdminException("Resposta do Keycloak sem access_token")
        val seconds = reply.body.get("expires_in")?.asLong() ?: 60
        tokenValidUntil = Instant.now(clock).plusSeconds((seconds - 15).coerceAtLeast(5))
        token = value
        return value
    }

    /**
     * So le o corpo de resposta 2xx. O erro do Jackson cita um trecho do corpo (que pode ser o token):
     * nunca sobe, vira uma mensagem fixa.
     */
    private fun execute(spec: RestClient.RequestHeadersSpec<*>): Reply =
        try {
            spec.exchange { _, response ->
                val status = response.statusCode.value()
                val body = if (response.statusCode.is2xxSuccessful) {
                    val bytes = response.body.readAllBytes()
                    if (bytes.isEmpty()) null else readJson(bytes)
                } else {
                    null
                }
                Reply(status, body)
            } ?: throw KeycloakAdminException("Resposta vazia do Keycloak")
        } catch (e: KeycloakAdminException) {
            throw e
        } catch (e: Exception) {
            throw KeycloakAdminException("Nao foi possivel falar com o Keycloak", e, uncertain = true)
        }

    private fun readJson(bytes: ByteArray): JsonNode =
        try {
            objectMapper.readTree(bytes)
        } catch (_: Exception) {
            throw KeycloakAdminException("Resposta do Keycloak em formato inesperado")
        }

    private data class Reply(val status: Int, val body: JsonNode?)
}

/**
 * No boot, cria no Keycloak a role de cada perfil que ainda nao existe la, inclusive os perfis anteriores a
 * integracao. Idempotente; com o Keycloak fora do ar so registra no log e o sistema sobe.
 */
@Component
@ConditionalOnProperty(value = ["dbinspector.keycloak.admin.enabled"], havingValue = "true")
class KeycloakRoleBootstrap(
    private val client: KeycloakAdminClient,
    private val roleRepository: RoleRepository
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(KeycloakRoleBootstrap::class.java)

    override fun run(vararg args: String?) {
        val names = roleRepository.findAllByOrderByNameAsc().map { it.name.uppercase() }
        var created = 0
        val unmanaged = mutableListOf<String>()
        for (name in names) {
            if (!KeycloakAdminClient.isValidRoleName(name)) {
                log.warn("Perfil '{}' nao foi criado no Keycloak: nome invalido para role. Renomeie o perfil", name)
                continue
            }
            try {
                when (client.ensureRole(name, allowUnmanaged = true)) {
                    RoleEnsureResult.CREATED -> created++
                    RoleEnsureResult.EXISTING_UNMANAGED -> unmanaged += name
                    RoleEnsureResult.EXISTING_MANAGED -> {}
                }
            } catch (e: KeycloakAdminException) {
                // Sem Keycloak, cada perfil seguinte custaria um timeout.
                log.warn("Conferencia dos perfis no Keycloak interrompida: {}", e.message)
                return
            }
        }
        if (unmanaged.isNotEmpty()) {
            log.warn(
                "Perfis cuja role no Keycloak nao foi criada pelo agroreports (confira quem a tem; nao sao " +
                    "renomeadas nem excluidas pela tela): {}",
                unmanaged
            )
        }
        log.info("Perfis conferidos no Keycloak: {} criado(s) de {}", created, names.size)
    }
}
