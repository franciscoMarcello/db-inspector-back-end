package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Valida o access token do Keycloak: so RS256, assinatura pelas chaves publicas do realm (JWKS em
 * memoria), issuer do realm e azp do client de login. Sem o azp, um token de outro sistema do mesmo
 * realm seria aceito aqui.
 */
@Service
class KeycloakJwtService(
    private val properties: DbInspectorProperties,
    @Qualifier("keycloakRestClient") private val http: RestClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(KeycloakJwtService::class.java)

    @Volatile
    private var keys: Map<String, PublicKey> = emptyMap()

    @Volatile
    private var keysLoadedAt: Instant = Instant.EPOCH

    init {
        val keycloak = properties.keycloak
        if (keycloak.enabled) {
            require(keycloak.url.isNotBlank() && keycloak.realm.isNotBlank() && keycloak.clientId.isNotBlank()) {
                "dbinspector.keycloak.url, realm e client-id sao obrigatorios com dbinspector.keycloak.enabled=true"
            }
        }
    }

    /** Token do Keycloak e RS256; o token local, aceito durante a transicao, e HS256. */
    fun isKeycloakToken(token: String): Boolean =
        runCatching { readHeader(token).alg == "RS256" }.getOrDefault(false)

    fun validate(token: String): Claims {
        val keycloak = properties.keycloak
        check(keycloak.enabled) { "Login pelo Keycloak desligado" }
        val header = readHeader(token)
        require(header.alg == "RS256") { "Algoritmo JWT '${header.alg}' nao permitido" }
        val claims = Jwts.parser()
            .verifyWith(findKey(header.kid))
            .requireIssuer(keycloak.issuer())
            .clockSkewSeconds(CLOCK_SKEW_SECONDS)
            .clock { Date.from(Instant.now(clock)) }
            .build()
            .parseSignedClaims(token)
            .payload
        require(claims["azp"]?.toString() == keycloak.clientId) { "Token emitido para outro client" }
        // ID token e refresh token do mesmo client tem typ ID/Refresh: so o access token autoriza a API.
        require(claims["typ"]?.toString() == "Bearer") { "Token do Keycloak nao e de acesso" }
        return claims
    }

    private fun readHeader(token: String): JwtHeader {
        val parts = token.split('.')
        require(parts.size == 3) { "JWT malformado" }
        val node = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]))
        return JwtHeader(
            alg = node["alg"]?.asText() ?: throw IllegalArgumentException("JWT sem alg"),
            kid = node["kid"]?.asText() ?: throw IllegalArgumentException("JWT sem kid")
        )
    }

    private fun findKey(kid: String): PublicKey {
        keys[kid]?.let { return it }
        reloadKeysIfDue(kid)
        return keys[kid] ?: throw IllegalArgumentException("Chave '$kid' nao encontrada no JWKS")
    }

    /**
     * kid desconhecido e o sinal de rotacao de chave. O intervalo minimo impede que tokens com kid inventado
     * virem uma requisicao ao Keycloak cada um; conferido dentro do bloco, as requisicoes que esperavam na
     * fila aproveitam a carga da primeira.
     */
    @Synchronized
    private fun reloadKeysIfDue(kid: String) {
        if (keys.containsKey(kid)) return
        if (keys.isNotEmpty() && Duration.between(keysLoadedAt, Instant.now(clock)) < MIN_RELOAD_INTERVAL) return
        reloadKeys()
    }

    private fun reloadKeys() {
        val body = http.get().uri(properties.keycloak.jwksUri()).retrieve().body(String::class.java)
            ?: throw IllegalStateException("JWKS vazio")
        // JwkSet tambem e um Map: `.keys` seriam os nomes dos campos, nao as chaves.
        // O realm publica tambem a chave de criptografia (use=enc, RSA-OAEP): so as de assinatura servem aqui.
        val loaded = Jwks.setParser().build().parse(body).getKeys()
            .filter { jwk -> jwk["use"]?.toString().let { it == null || it == "sig" } }
            .mapNotNull { jwk -> (jwk.toKey() as? PublicKey)?.let { jwk.id to it } }
            .toMap()
        require(loaded.isNotEmpty()) { "JWKS sem chave publica" }
        keys = loaded
        keysLoadedAt = Instant.now(clock)
        log.info("JWKS do Keycloak carregado: {} chave(s)", loaded.size)
    }

    private data class JwtHeader(val alg: String, val kid: String)

    private companion object {
        const val CLOCK_SKEW_SECONDS = 30L
        val MIN_RELOAD_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
