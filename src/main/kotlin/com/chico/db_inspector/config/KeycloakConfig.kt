package com.chico.dbinspector.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class KeycloakConfig {
    /** Cliente HTTP do Keycloak (JWKS e API de administracao), com timeout proprio. */
    @Bean
    fun keycloakRestClient(properties: DbInspectorProperties): RestClient {
        val timeout = Duration.ofMillis(properties.keycloak.admin.timeoutMs.coerceAtLeast(1_000))
        return RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(timeout)
                    setReadTimeout(timeout)
                }
            )
            .build()
    }
}
