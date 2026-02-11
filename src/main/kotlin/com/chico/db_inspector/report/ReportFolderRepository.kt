package com.chico.dbinspector.report

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportFolderRepository : JpaRepository<ReportFolderEntity, UUID> {
    fun existsByNameIgnoreCase(name: String): Boolean
}
