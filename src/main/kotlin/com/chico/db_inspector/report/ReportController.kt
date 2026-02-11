package com.chico.dbinspector.report

import com.chico.dbinspector.web.UpstreamContext
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/db/reports")
class ReportController(
    private val reportService: ReportService
) {
    @GetMapping
    fun list(): List<ReportResponse> = reportService.list()

    @PostMapping
    fun create(@Valid @RequestBody body: ReportRequest): ReportResponse =
        reportService.create(body)

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ReportRequest
    ): ReportResponse = reportService.update(id, body)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        reportService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/run")
    fun run(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ReportRunRequest?,
        ctx: UpstreamContext
    ): ReportRunResponse = reportService.run(id, ctx, body ?: ReportRunRequest())

    @PostMapping("/{id}/pdf")
    fun pdf(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ReportRunRequest?,
        ctx: UpstreamContext
    ): ResponseEntity<ByteArray> {
        val payload = body ?: ReportRunRequest()
        val pdfBytes = reportService.generatePdf(id, ctx, payload)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-$id.pdf\"")
            .body(pdfBytes)
    }
}
