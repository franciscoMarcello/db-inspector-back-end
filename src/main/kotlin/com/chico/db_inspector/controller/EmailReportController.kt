package com.chico.dbinspector.controller

import com.chico.dbinspector.email.EmailReportRequest
import com.chico.dbinspector.email.EmailReportScheduler
import com.chico.dbinspector.email.EmailReportService
import com.chico.dbinspector.email.EmailTestRequest
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/db/email")
class EmailReportController(
    private val scheduler: EmailReportScheduler,
    private val emailService: EmailReportService
) {

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('EMAIL_SEND')")
    fun sendReport(
        @Valid @RequestBody body: EmailReportRequest,
        ctx: UpstreamContext
    ): ResponseEntity<Any> {
        val query = body.sql.trim()
        require(query.isNotEmpty()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(query)

        val wantsSchedule = !body.time.isNullOrBlank() || !body.days.isNullOrEmpty()
        if (wantsSchedule) {
            require(!body.time.isNullOrBlank() && !body.days.isNullOrEmpty()) {
                "Para agendar informe 'time' (HH:mm) e pelo menos um dia em 'days'"
            }
            val scheduled = scheduler.schedule(body, ctx, query)
            return ResponseEntity.ok(
                mapOf(
                    "status" to "scheduled",
                    "scheduleId" to scheduled.id,
                    "cron" to scheduled.cron,
                    "nextRun" to scheduled.nextRun?.toString()
                )
            )
        }

        val sendResult = scheduler.sendNow(body, ctx, query)
        return ResponseEntity.ok(
            mapOf(
                "status" to "sent",
                "previewRows" to sendResult.previewRows,
                "attachedXlsx" to sendResult.attachedXlsx
            )
        )
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('EMAIL_TEST')")
    fun sendTest(
        @Valid @RequestBody body: EmailTestRequest
    ): ResponseEntity<Any> {
        emailService.sendTestEmail(body)
        return ResponseEntity.ok(mapOf("status" to "sent"))
    }
}
