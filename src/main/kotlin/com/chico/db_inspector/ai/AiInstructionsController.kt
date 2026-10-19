package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
@RequestMapping("/api/db/ai")
@PreAuthorize("hasAuthority('REPORT_READ')")
class AiInstructionsController(
    private val service: AiInstructionsService,
    private val properties: DbInspectorProperties
) {
    @GetMapping("/instrucoes", produces = ["text/markdown;charset=UTF-8"])
    fun instructions(): String = service.read(null, baseUrl())

    @GetMapping("/instrucoes/arquivos")
    fun files(): List<String> = service.paths()

    @GetMapping("/instrucoes/arquivo", produces = ["text/plain;charset=UTF-8"])
    fun file(@RequestParam caminho: String): String = service.read(caminho, baseUrl())

    @GetMapping("/openapi.yaml", produces = ["application/yaml;charset=UTF-8"])
    fun openApi(): String = service.openApi(baseUrl())

    /** O que o botao "Instrucoes para IA" baixa. */
    @GetMapping("/pacote")
    fun packageZip(): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dbinspector-ia.zip\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(service.packageZip(baseUrl()))

    /** URL configurada, ou deduzida da requisicao (respeita X-Forwarded-* se o proxy os enviar). */
    private fun baseUrl(): String =
        properties.ai.publicUrl.takeIf { it.isNotBlank() }
            ?: ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
}
