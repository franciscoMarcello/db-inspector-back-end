package com.chico.dbinspector.ai

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Gestao de tokens de IA. So ADMIN logado por JWT: o filtro recusa token de IA nesta rota, senao
 * um token emitiria outro e a expiracao deixaria de valer.
 */
@RestController
@RequestMapping("/api/db/ai/tokens")
@PreAuthorize("hasRole('ADMIN')")
class AiTokenController(
    private val service: AiTokenService
) {
    @GetMapping
    fun list(): List<AiTokenResponse> = service.list()

    @PostMapping
    fun create(@Valid @RequestBody body: AiTokenCreateRequest): ResponseEntity<AiTokenCreatedResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(body))

    @DeleteMapping("/{id}")
    fun revoke(@PathVariable id: UUID): ResponseEntity<Void> {
        service.revoke(id)
        return ResponseEntity.noContent().build()
    }
}
