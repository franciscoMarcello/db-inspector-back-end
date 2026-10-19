package com.chico.dbinspector.report

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReportVersionRepository : JpaRepository<ReportVersionEntity, UUID> {
    fun findAllByReportIdOrderByVersionNumberDesc(reportId: UUID): List<ReportVersionEntity>
    fun findByReportIdAndVersionNumber(reportId: UUID, versionNumber: Int): ReportVersionEntity?
    fun findFirstByReportIdAndStatus(reportId: UUID, status: String): ReportVersionEntity?
    fun findAllByReportIdAndStatus(reportId: UUID, status: String): List<ReportVersionEntity>
    fun existsByReportId(reportId: UUID): Boolean
    fun deleteAllByReportId(reportId: UUID)

    @Query("select coalesce(max(v.versionNumber), 0) from ReportVersionEntity v where v.report.id = :reportId")
    fun maxVersionNumber(reportId: UUID): Int

    @Query("select v.report.id, v.versionNumber from ReportVersionEntity v where v.status = 'DRAFT'")
    fun findOpenDraftNumbers(): List<Array<Any>>
}
