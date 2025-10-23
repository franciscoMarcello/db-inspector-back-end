package com.chico.dbinspector.service
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType

@Component
class SqlExecClient(@Qualifier("sqlExecWebClient") private val webClient: WebClient) {

    fun exec(query: String, asDict: Boolean = true, withDescription: Boolean = true): Map<String, Any?> {
        val payload = mapOf(
            "query" to query,
            "asDict" to asDict,
            "withDescription" to withDescription
        )

        return webClient.post()
            .uri("") // 👈 NADA de "/sql/exec/" aqui (baseUrl já tem)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .onStatus({ it.isError }) { resp ->
                resp.bodyToMono(String::class.java)
                    .map { IllegalStateException("HTTP ${resp.statusCode()} - $it") }
            }
            .bodyToMono(object : ParameterizedTypeReference<Map<String, Any?>>() {})
            .block()!!
    }
}