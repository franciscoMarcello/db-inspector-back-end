package com.chico.dbinspector.report

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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
@RequestMapping("/api/db/report-templates")
class ReportJasperTemplateController(
    private val service: ReportJasperTemplateService
) {
    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATE_READ')")
    fun list(): List<JasperTemplateResponse> = service.list()

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_READ')")
    fun get(@PathVariable id: UUID): JasperTemplateResponse = service.get(id)

    @PostMapping
    @PreAuthorize("hasAuthority('TEMPLATE_WRITE')")
    fun create(@Valid @RequestBody body: JasperTemplateRequest): JasperTemplateResponse =
        service.create(body)

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_WRITE')")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: JasperTemplateRequest
    ): JasperTemplateResponse = service.update(id, body)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('TEMPLATE_WRITE')")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
