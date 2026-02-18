package com.chico.dbinspector.controller

import com.chico.dbinspector.email.EmailReportRequest
import com.chico.dbinspector.email.EmailReportScheduler
import com.chico.dbinspector.email.EmailScheduleResponse
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/db/email/schedules")
class EmailScheduleController(
    private val scheduler: EmailReportScheduler
) {
    @GetMapping
    fun list(): List<EmailScheduleResponse> = scheduler.listSchedules()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): EmailScheduleResponse = scheduler.getSchedule(id)

    @PostMapping
    fun create(
        @Valid @RequestBody body: EmailReportRequest,
        ctx: UpstreamContext
    ): EmailScheduleResponse {
        val query = body.sql.trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(query)
        require(!body.time.isNullOrBlank() && !body.days.isNullOrEmpty()) {
            "Para agendar informe 'time' (HH:mm) e pelo menos um dia em 'days'"
        }
        return scheduler.scheduleDetailed(body, ctx, query)
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody body: EmailReportRequest,
        ctx: UpstreamContext
    ): EmailScheduleResponse {
        val query = body.sql.trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(query)
        require(!body.time.isNullOrBlank() && !body.days.isNullOrEmpty()) {
            "Para agendar informe 'time' (HH:mm) e pelo menos um dia em 'days'"
        }
        return scheduler.updateSchedule(id, body, ctx, query)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        scheduler.deleteSchedule(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: String): ResponseEntity<Void> {
        scheduler.pauseSchedule(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/resume")
    fun resume(@PathVariable id: String): ResponseEntity<Void> {
        scheduler.resumeSchedule(id)
        return ResponseEntity.noContent().build()
    }
}
