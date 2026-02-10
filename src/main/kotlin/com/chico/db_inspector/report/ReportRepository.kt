package com.chico.dbinspector.report

import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface ReportRepository : JpaRepository<ReportEntity, UUID> {
    @EntityGraph(attributePaths = ["variables", "folder"])
    override fun findAll(sort: Sort): List<ReportEntity>

    @EntityGraph(attributePaths = ["variables", "folder"])
    override fun findById(id: UUID): Optional<ReportEntity>

    fun existsByFolderId(folderId: UUID): Boolean
}
