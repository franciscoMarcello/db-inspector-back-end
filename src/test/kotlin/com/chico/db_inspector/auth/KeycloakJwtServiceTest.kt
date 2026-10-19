package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import javax.crypto.spec.SecretKeySpec

class KeycloakJwtServiceTest {

    private val clock = MutableClock(Instant.parse("2026-10-19T12:00:00Z"))
    private val properties = DbInspectorProperties().apply {
        keycloak.enabled = true
        keycloak.url = "https://sso.exemplo.com.br/"
        keycloak.realm = "rovema"
        keycloak.clientId = "agroreports"
    }
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val service = KeycloakJwtService(properties, builder.build(), ObjectMapper(), clock)

    private val key1 = rsaKeyPair()
    private val key2 = rsaKeyPair()

    @Test
    fun `aceita token RS256 do realm emitido para o client de login`() {
        expectJwks("k1" to key1)

        val claims = service.validate(token())

        assertEquals("kc-ana-0001", claims.subject)
        server.verify()
    }

    @Test
    fun `recusa token de outro client do mesmo realm`() {
        expectJwks("k1" to key1)

        val error = assertThrows(IllegalArgumentException::class.java) { service.validate(token(azp = "sapvema")) }

        assertTrue(error.message.orEmpty().contains("outro client"))
    }

    @Test
    fun `recusa ID token do mesmo client como bearer da API`() {
        expectJwks("k1" to key1)

        val error = assertThrows(IllegalArgumentException::class.java) { service.validate(token(typ = "ID")) }

        assertTrue(error.message.orEmpty().contains("nao e de acesso"))
    }

    @Test
    fun `recusa token de outro realm`() {
        expectJwks("k1" to key1)

        assertThrows(Exception::class.java) {
            service.validate(token(issuer = "https://sso.exemplo.com.br/realms/outro"))
        }
    }

    @Test
    fun `recusa token expirado alem da tolerancia de relogio`() {
        expectJwks("k1" to key1)

        assertThrows(Exception::class.java) {
            service.validate(token(expiresAt = clock.instant().minusSeconds(120)))
        }
    }

    @Test
    fun `recusa HS256 mesmo assinado e sem consultar o JWKS`() {
        val hmac = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        val forged = Jwts.builder().header().keyId("k1").and()
            .issuer(ISSUER).claim("azp", "agroreports").subject("kc-ana-0001")
            .expiration(Date.from(clock.instant().plusSeconds(300)))
            .signWith(hmac, Jwts.SIG.HS256)
            .compact()

        assertFalse(service.isKeycloakToken(forged))
        assertThrows(IllegalArgumentException::class.java) { service.validate(forged) }
        server.verify()
    }

    @Test
    fun `assinatura com chave fora do JWKS e recusada`() {
        expectJwks("k1" to key1)

        assertThrows(Exception::class.java) { service.validate(token(signer = key2.private)) }
    }

    @Test
    fun `kid novo recarrega o JWKS no maximo a cada 30 segundos`() {
        expectJwks("k1" to key1)
        expectJwks("k1" to key1, "k2" to key2)
        service.validate(token())
        val rotated = token(kid = "k2", signer = key2.private)

        // Logo depois da carga: kid desconhecido nao vira nova requisicao ao Keycloak.
        assertThrows(IllegalArgumentException::class.java) { service.validate(rotated) }

        clock.advance(Duration.ofSeconds(31))
        assertEquals("kc-ana-0001", service.validate(rotated).subject)
        server.verify()
    }

    @Test
    fun `identifica o token do Keycloak pelo algoritmo`() {
        assertTrue(service.isKeycloakToken(token()))
        assertFalse(service.isKeycloakToken("rpt_abc"))
        assertFalse(service.isKeycloakToken("nao.e.jwt"))
    }

    private fun token(
        kid: String = "k1",
        signer: PrivateKey = key1.private,
        azp: String = "agroreports",
        issuer: String = ISSUER,
        expiresAt: Instant = clock.instant().plusSeconds(300),
        typ: String = "Bearer"
    ): String =
        Jwts.builder()
            .header().keyId(kid).and()
            .issuer(issuer)
            .subject("kc-ana-0001")
            .claim("azp", azp)
            .claim("typ", typ)
            .issuedAt(Date.from(clock.instant()))
            .expiration(Date.from(expiresAt))
            .signWith(signer, Jwts.SIG.RS256)
            .compact()

    @Test
    fun `chave de criptografia do realm nao valida assinatura`() {
        expectJwks("k1" to key1)

        assertThrows(IllegalArgumentException::class.java) { service.validate(token(kid = "enc-1", signer = key2.private)) }
    }

    /** Como o JWKS real do realm: alem das chaves de assinatura, uma de criptografia (RSA-OAEP). */
    private fun expectJwks(vararg keys: Pair<String, KeyPair>) {
        val signing = keys.map { (kid, pair) -> jwk(kid, pair, use = "sig", alg = "RS256") }
        val body = (signing + jwk("enc-1", key2, use = "enc", alg = "RSA-OAEP"))
            .joinToString(",", prefix = "{\"keys\":[", postfix = "]}")
        server.expect(ExpectedCount.once(), requestTo("$ISSUER/protocol/openid-connect/certs"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun jwk(kid: String, pair: KeyPair, use: String, alg: String): String {
        val public = pair.public as RSAPublicKey
        return """{"kty":"RSA","kid":"$kid","use":"$use","alg":"$alg","n":"${b64(public.modulus)}","e":"${b64(public.publicExponent)}"}"""
    }

    private fun b64(value: BigInteger): String {
        val bytes = value.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private companion object {
        const val ISSUER = "https://sso.exemplo.com.br/realms/rovema"
    }
}
