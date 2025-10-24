package com.chico.dbinspector.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Component
class SqlExecClient(@Qualifier("sqlExecWebClient") private val webClient: WebClient) {

    private val mapper = jacksonObjectMapper()

    fun exec(query: String, asDict: Boolean = true, withDescription: Boolean = true): Map<String, Any?> {
        val payload = mapOf("query" to query, "asDict" to asDict, "withDescription" to withDescription)

        // Garanta que o baseUrl do bean termina em .../sql/exec/ (com / no final)
        val entity: ResponseEntity<String> = webClient.post()
            .uri("") // baseUrl já aponta para o endpoint final
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .exchangeToMono { resp ->
                val status = resp.statusCode()
                val headers = resp.headers().asHttpHeaders()
                val location = headers.location

                resp.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .flatMap { body ->
                        if (status.is3xxRedirection) {
                            if (location == null) {
                                return@flatMap Mono.error<ResponseEntity<String>>(IllegalStateException("3xx sem Location"))
                            }
                            // re-POST mantendo método e payload
                            webClient.post().uri(location)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .bodyValue(payload)
                                .retrieve() // aqui pode usar toEntity
                                .toEntity(String::class.java)
                        } else {
                            // Monte um ResponseEntity manualmente
                            val e = ResponseEntity.status(status.value())
                                .headers(headers)
                                .body(body)
                            Mono.just(e)
                        }
                    }
            }
            .block() ?: error("Sem resposta")

        val status = entity.statusCode
        val headers = entity.headers
        val ct: MediaType? = headers.contentType
        val raw = entity.body ?: ""

        if (!status.is2xxSuccessful) error("HTTP $status, CT=$ct, Location=${headers.location}, body=${raw.take(800)}")
        if (raw.isBlank()) error("HTTP $status com corpo vazio. CT=$ct, Location=${headers.location}")

        val text = raw.trimStart()
        val looksJson = text.startsWith("{") || text.startsWith("[")
        val isJson = ct?.let { it.isCompatibleWith(MediaType.APPLICATION_JSON) || it.subtype.endsWith("+json") } ?: false
        if (!isJson && !looksJson) error("Esperava JSON mas veio $ct. Prévia=${text.take(800)}")

        return mapper.readValue(text)
    }
}
