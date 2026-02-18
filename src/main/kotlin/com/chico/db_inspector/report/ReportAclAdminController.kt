package com.chico.dbinspector.report

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class ReportAclAdminController(
    private val aclService: ReportAclAdminService
) {
    @GetMapping("/report-folders/{id}/acl")
    fun listFolderAcl(@PathVariable id: UUID): List<ResourceAclResponse> = aclService.listFolderAcl(id)

    @PutMapping("/report-folders/{id}/acl")
    fun upsertFolderAcl(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ResourceAclRequest
    ): ResourceAclResponse = aclService.upsertFolderAcl(id, request)

    @DeleteMapping("/report-folders/{id}/acl")
    fun removeFolderAcl(
        @PathVariable id: UUID,
        @RequestParam subjectType: String,
        @RequestParam subject: String
    ): ResponseEntity<Void> {
        aclService.removeFolderAcl(id, subjectType, subject)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/reports/{id}/acl")
    fun listReportAcl(@PathVariable id: UUID): List<ResourceAclResponse> = aclService.listReportAcl(id)

    @PutMapping("/reports/{id}/acl")
    fun upsertReportAcl(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ResourceAclRequest
    ): ResourceAclResponse = aclService.upsertReportAcl(id, request)

    @DeleteMapping("/reports/{id}/acl")
    fun removeReportAcl(
        @PathVariable id: UUID,
        @RequestParam subjectType: String,
        @RequestParam subject: String
    ): ResponseEntity<Void> {
        aclService.removeReportAcl(id, subjectType, subject)
        return ResponseEntity.noContent().build()
    }
}
