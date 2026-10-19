package com.chico.dbinspector.ai

import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Decisao do operador sobre os pedidos de liberacao. Fica fora de /api/db/ai/ de proposito: o
 * filtro so deixa token de IA entrar la, entao nenhum token aprova o proprio pedido.
 */
@RestController
@RequestMapping("/api/db/ai-approvals")
@PreAuthorize("hasRole('ADMIN')")
class AiApprovalAdminController(
    private val service: AiApprovalService
) {
    @GetMapping
    fun list(@RequestParam(required = false) status: String?): List<AiApprovalResponse> = service.list(status)

    @GetMapping("/pending-count")
    fun pendingCount(): Map<String, Long> = mapOf("pending" to service.pendingCount())

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) body: AiApprovalDecisionRequest?
    ): AiApprovalResponse = service.approve(id, body ?: AiApprovalDecisionRequest())

    @PostMapping("/{id}/deny")
    fun deny(
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) body: AiApprovalDecisionRequest?
    ): AiApprovalResponse = service.deny(id, body ?: AiApprovalDecisionRequest())
}
