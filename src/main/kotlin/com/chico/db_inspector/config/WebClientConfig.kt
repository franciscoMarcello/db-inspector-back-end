// com.chico.dbinspector.config.WebClientConfig
package com.chico.dbinspector.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {
    @Bean
    fun sqlExecWebClient(builder: WebClient.Builder): WebClient =
        builder
            .exchangeStrategies(
                ExchangeStrategies.builder().codecs {
                    it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)
                }.build()
            )
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
}
