package com.chico.dbinspector.report

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportRepository : JpaRepository<ReportEntity, UUID>
