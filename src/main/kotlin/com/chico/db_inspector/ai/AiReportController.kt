package com.chico.dbinspector.ai

import com.chico.dbinspector.report.ReportVersionSummaryResponse
import com.chico.dbinspector.web.UpstreamContext
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** API de relatorios para agentes de IA (qualquer fornecedor). Mesmo contrato do MCP. */
@RestController
@RequestMapping("/api/db/ai")
class AiReportController(
    private val reportService: AiReportService,
    private val catalogService: AiCatalogService
) {
    @GetMapping("/catalogo/schemas")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun schemas(): Map<String, Any?> =
        mapOf("geradoEm" to catalogService.generatedAt(), "schemas" to catalogService.schemas())

    @GetMapping("/catalogo/tabelas")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun searchTables(
        @RequestParam busca: String,
        @RequestParam(required = false) schema: String?,
        @RequestParam(defaultValue = "30") limite: Int
    ): List<AiTableMatch> = catalogService.search(busca, schema, limite)

    @GetMapping("/catalogo/tabelas/{schema}/{tabela}")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun describeTable(
        @PathVariable schema: String,
        @PathVariable tabela: String,
        ctx: UpstreamContext
    ): AiTableDescription = catalogService.describe(schema, tabela, ctx)

    @GetMapping("/pastas")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun folders(): List<AiFolder> = reportService.folders()

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('TEMPLATE_READ')")
    fun templates(): List<AiTemplateSummary> = reportService.templates()

    @GetMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_READ')")
    fun template(@PathVariable id: UUID): AiTemplateDetail = reportService.template(id)

    @GetMapping("/relatorios")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun reports(): List<AiReportSummary> = reportService.reports()

    @GetMapping("/relatorios/{id}")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun report(@PathVariable id: UUID): AiReportDetail = reportService.report(id)

    @GetMapping("/relatorios/{id}/versoes")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun versions(@PathVariable id: UUID): List<ReportVersionSummaryResponse> = reportService.versions(id)

    @GetMapping("/relatorios/{id}/versoes/{versao}")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun version(@PathVariable id: UUID, @PathVariable versao: Int): AiReportDetail = reportService.version(id, versao)

    @PostMapping("/relatorios/validar")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun validate(@Valid @RequestBody body: AiReportValidationRequest, ctx: UpstreamContext): AiReportValidationResponse =
        reportService.validate(body, ctx)

    @PostMapping("/relatorios")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun create(@Valid @RequestBody body: AiReportDraftRequest, ctx: UpstreamContext): ResponseEntity<AiReportDraftResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(reportService.createDraft(body, ctx))

    @PutMapping("/relatorios/{id}/rascunho")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun saveDraft(
        @PathVariable id: UUID,
        @Valid @RequestBody body: AiReportDraftRequest,
        ctx: UpstreamContext
    ): AiReportDraftResponse = reportService.saveDraft(id, body, ctx)

    @PostMapping("/relatorios/{id}/versoes/{versao}/previa")
    @PreAuthorize("hasAuthority('REPORT_WRITE') and hasAuthority('REPORT_RUN')")
    fun preview(
        @PathVariable id: UUID,
        @PathVariable versao: Int,
        @RequestBody(required = false) body: AiPreviewRequest?,
        ctx: UpstreamContext
    ): AiPreviewResponse = reportService.preview(id, versao, body ?: AiPreviewRequest(), ctx)

    @PostMapping("/relatorios/{id}/versoes/{versao}/pdf/resumo")
    @PreAuthorize("hasAuthority('REPORT_WRITE') and hasAuthority('REPORT_RUN')")
    fun pdfSummary(
        @PathVariable id: UUID,
        @PathVariable versao: Int,
        @RequestBody(required = false) body: AiPreviewRequest?,
        ctx: UpstreamContext
    ): AiPdfPreviewResponse = reportService.pdf(id, versao, body ?: AiPreviewRequest(), ctx).second

    @PostMapping("/relatorios/{id}/versoes/{versao}/pdf")
    @PreAuthorize("hasAuthority('REPORT_WRITE') and hasAuthority('REPORT_RUN')")
    fun pdf(
        @PathVariable id: UUID,
        @PathVariable versao: Int,
        @RequestBody(required = false) body: AiPreviewRequest?,
        ctx: UpstreamContext
    ): ResponseEntity<ByteArray> {
        val bytes = reportService.pdf(id, versao, body ?: AiPreviewRequest(), ctx).first
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"previa-$id-v$versao.pdf\"")
            .body(bytes)
    }
}
