package com.chico.dbinspector.dashboard

import com.chico.dbinspector.web.UpstreamContext
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/db/dashboards")
class DashboardController(
    private val dashboardService: DashboardService
) {
    @GetMapping
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    fun list(
        @RequestParam(required = false) system: DashboardSystem?,
        @RequestParam(required = false) archived: Boolean?
    ): List<DashboardResponse> = dashboardService.list(DashboardFilter(system = system, archived = archived))

    @PostMapping
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun create(@Valid @RequestBody body: DashboardRequest): DashboardResponse = dashboardService.create(body)

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    fun get(@PathVariable id: UUID): DashboardResponse = dashboardService.get(id)

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun update(@PathVariable id: UUID, @Valid @RequestBody body: DashboardRequest): DashboardResponse =
        dashboardService.update(id, body)

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        dashboardService.archive(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/widgets")
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun createWidget(
        @PathVariable id: UUID,
        @Valid @RequestBody body: DashboardWidgetRequest
    ): DashboardWidgetResponse = dashboardService.createWidget(id, body)

    @PutMapping("/{id}/widgets/{widgetId}")
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun updateWidget(
        @PathVariable id: UUID,
        @PathVariable widgetId: UUID,
        @Valid @RequestBody body: DashboardWidgetRequest
    ): DashboardWidgetResponse = dashboardService.updateWidget(id, widgetId, body)

    @DeleteMapping("/{id}/widgets/{widgetId}")
    @PreAuthorize("hasAuthority('DASHBOARD_WRITE')")
    fun deleteWidget(@PathVariable id: UUID, @PathVariable widgetId: UUID): ResponseEntity<Void> {
        dashboardService.deleteWidget(id, widgetId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/widgets/{widgetId}/preview")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    fun preview(
        @PathVariable id: UUID,
        @PathVariable widgetId: UUID,
        @RequestBody(required = false) body: DashboardWidgetRunRequest?,
        ctx: UpstreamContext
    ): DashboardWidgetRunResponse = dashboardService.previewWidget(id, widgetId, ctx, body ?: DashboardWidgetRunRequest())

    @PostMapping("/{id}/widgets/{widgetId}/run")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    fun run(
        @PathVariable id: UUID,
        @PathVariable widgetId: UUID,
        @RequestBody(required = false) body: DashboardWidgetRunRequest?,
        ctx: UpstreamContext
    ): DashboardWidgetRunResponse = dashboardService.runWidget(id, widgetId, ctx, body ?: DashboardWidgetRunRequest())
}
