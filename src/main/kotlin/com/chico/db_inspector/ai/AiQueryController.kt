package com.chico.dbinspector.ai

import com.chico.dbinspector.web.UpstreamContext
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Consultas exploratorias e pedidos de liberacao, do lado do agente. */
@RestController
@RequestMapping("/api/db/ai")
@PreAuthorize("hasAuthority('REPORT_RUN')")
class AiQueryController(
    private val queryService: AiQueryService,
    private val approvalService: AiApprovalService
) {
    @PostMapping("/consultas")
    fun query(@Valid @RequestBody body: AiQueryRequest, ctx: UpstreamContext): AiQueryResponse =
        queryService.query(body, ctx)

    @PostMapping("/consultas/explicar")
    fun explain(@Valid @RequestBody body: AiExplainRequest, ctx: UpstreamContext): AiExplainResponse =
        queryService.explain(body, ctx)

    @PostMapping("/liberacoes")
    fun requestApproval(@Valid @RequestBody body: AiApprovalRequest): ResponseEntity<AiApprovalResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(approvalService.request(body))

    @GetMapping("/liberacoes/{id}")
    fun approval(@PathVariable id: UUID): AiApprovalResponse = approvalService.getForToken(id)
}
