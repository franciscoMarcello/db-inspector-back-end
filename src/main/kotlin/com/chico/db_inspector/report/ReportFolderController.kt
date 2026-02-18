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
@RequestMapping("/api/db/report-folders")
class ReportFolderController(
    private val folderService: ReportFolderService
) {
    @GetMapping
    @PreAuthorize("hasAuthority('FOLDER_READ')")
    fun list(): List<ReportFolderResponse> = folderService.list()

    @PostMapping
    @PreAuthorize("hasAuthority('FOLDER_WRITE')")
    fun create(@Valid @RequestBody body: ReportFolderRequest): ReportFolderResponse =
        folderService.create(body)

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('FOLDER_WRITE')")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ReportFolderRequest
    ): ReportFolderResponse = folderService.update(id, body)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('FOLDER_WRITE')")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        folderService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
