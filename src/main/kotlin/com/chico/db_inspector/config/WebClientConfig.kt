package com.chico.dbinspector.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig(
    @Value("\${dbinspector.sqlExecBaseUrl}") private val baseUrl: String
) {
    @Bean
    open fun sqlExecWebClient(): WebClient {
        val exchangeStrategies = ExchangeStrategies.builder()
            .codecs { config ->
                // Aumenta o limite de memória de 256 KB para 50 MB
                config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)
            }
            .build()

        return WebClient.builder()
            .baseUrl(baseUrl)
            .exchangeStrategies(exchangeStrategies)
            .build()
    }
}
