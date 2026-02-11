package com.chico.dbinspector.report

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportJasperTemplateRepository : JpaRepository<ReportJasperTemplateEntity, UUID> {
    fun existsByNameIgnoreCase(name: String): Boolean
}
