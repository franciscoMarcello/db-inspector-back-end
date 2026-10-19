package com.chico.dbinspector.report

import com.chico.dbinspector.web.UpstreamContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/db/reports/{reportId}/versions")
class ReportVersionController(
    private val versionService: ReportVersionService,
    private val reportService: ReportService
) {
    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun list(@PathVariable reportId: UUID): List<ReportVersionSummaryResponse> =
        versionService.list(reportId)

    @GetMapping("/{versionNumber}")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    fun get(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int
    ): ReportVersionResponse = versionService.get(reportId, versionNumber)

    @PostMapping("/{versionNumber}/run")
    @PreAuthorize("hasAuthority('REPORT_WRITE') and hasAuthority('REPORT_RUN')")
    fun run(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int,
        @RequestBody(required = false) body: ReportRunRequest?,
        ctx: UpstreamContext
    ): ReportRunResponse = reportService.runVersion(reportId, versionNumber, ctx, body ?: ReportRunRequest())

    @PostMapping("/{versionNumber}/pdf")
    @PreAuthorize("hasAuthority('REPORT_WRITE') and hasAuthority('REPORT_RUN')")
    fun pdf(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int,
        @RequestBody(required = false) body: ReportRunRequest?,
        ctx: UpstreamContext
    ): ResponseEntity<ByteArray> {
        val pdfBytes = reportService.generateVersionPdf(reportId, versionNumber, ctx, body ?: ReportRunRequest())
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-$reportId-v$versionNumber.pdf\"")
            .body(pdfBytes)
    }

    @PostMapping("/{versionNumber}/publish")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun publish(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int,
        @RequestBody(required = false) body: ReportVersionActionRequest?
    ): ReportVersionResponse = versionService.publish(reportId, versionNumber, body?.note, body?.expectedRevision)

    @PostMapping("/{versionNumber}/reuse")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun reuse(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int,
        @RequestBody(required = false) body: ReportVersionActionRequest?
    ): ReportVersionResponse = versionService.reuseAsDraft(reportId, versionNumber, body?.note)

    @PostMapping("/{versionNumber}/restore")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun restore(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int,
        @RequestBody(required = false) body: ReportVersionActionRequest?
    ): ReportVersionResponse = versionService.restore(reportId, versionNumber, body?.note)

    @PostMapping("/{versionNumber}/discard")
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    fun discard(
        @PathVariable reportId: UUID,
        @PathVariable versionNumber: Int
    ): ResponseEntity<Map<String, Boolean>> {
        val reportDeleted = versionService.discard(reportId, versionNumber)
        return ResponseEntity.ok(mapOf("reportDeleted" to reportDeleted))
    }
}
