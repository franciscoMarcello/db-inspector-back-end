package com.chico.dbinspector.dashboard

import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface DashboardRepository : JpaRepository<DashboardEntity, UUID> {
    @EntityGraph(attributePaths = ["widgets"])
    override fun findById(id: UUID): Optional<DashboardEntity>

    @EntityGraph(attributePaths = ["widgets"])
    override fun findAll(sort: Sort): List<DashboardEntity>
}

interface DashboardWidgetRepository : JpaRepository<DashboardWidgetEntity, UUID> {
    @EntityGraph(attributePaths = ["dashboard"])
    override fun findById(id: UUID): Optional<DashboardWidgetEntity>
}
