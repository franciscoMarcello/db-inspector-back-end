package com.chico.dbinspector.report

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface ReportFolderAclRepository : JpaRepository<ReportFolderAclEntity, UUID> {
    fun findAllByFolderId(folderId: UUID): List<ReportFolderAclEntity>
    fun findByFolderIdAndSubjectTypeAndSubjectKeyIgnoreCase(
        folderId: UUID,
        subjectType: String,
        subjectKey: String
    ): Optional<ReportFolderAclEntity>
    fun existsByFolderId(folderId: UUID): Boolean
}

interface ReportAclRepository : JpaRepository<ReportAclEntity, UUID> {
    fun findAllByReportId(reportId: UUID): List<ReportAclEntity>
    fun findByReportIdAndSubjectTypeAndSubjectKeyIgnoreCase(
        reportId: UUID,
        subjectType: String,
        subjectKey: String
    ): Optional<ReportAclEntity>
    fun existsByReportId(reportId: UUID): Boolean
}
