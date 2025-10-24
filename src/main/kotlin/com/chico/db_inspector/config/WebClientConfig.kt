package com.chico.dbinspector.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig(
    @Value("\${dbinspector.sqlExecBaseUrl}") private val baseUrl: String,
    @Value("\${dbinspector.apitoken}") private val token: String
) {
    @Bean
    fun sqlExecWebClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .defaultHeaders { h -> h.setBearerAuth(token) }
            .build()
}

