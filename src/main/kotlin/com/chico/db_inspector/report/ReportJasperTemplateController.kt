package com.chico.dbinspector.report

import jakarta.validation.Valid
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
@RequestMapping("/api/db/report-templates")
class ReportJasperTemplateController(
    private val service: ReportJasperTemplateService
) {
    @GetMapping
    fun list(): List<JasperTemplateResponse> = service.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): JasperTemplateResponse = service.get(id)

    @PostMapping
    fun create(@Valid @RequestBody body: JasperTemplateRequest): JasperTemplateResponse =
        service.create(body)

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: JasperTemplateRequest
    ): JasperTemplateResponse = service.update(id, body)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
