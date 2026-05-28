package com.chico.dbinspector.email

import com.chico.dbinspector.report.ReportRunRequest
import com.chico.dbinspector.report.ReportService
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.matchers.GroupMatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.UUID

data class ScheduledReportResult(
    val id: String,
    val cron: String,
    val nextRun: ZonedDateTime?
)

@Service
class EmailReportScheduler(
    private val scheduler: Scheduler,
    private val sqlExecClient: SqlExecClient,
    private val emailService: EmailReportService,
    private val comparisonService: EmailComparisonService,
    private val hanaQueryService: HanaQueryService,
    private val reportService: ReportService,
    private val cronService: EmailScheduleCronService
) {
    private val log = LoggerFactory.getLogger(EmailReportScheduler::class.java)

    fun sendNow(body: EmailReportRequest, ctx: UpstreamContext, query: String): EmailSendResult {
        ReadOnlySqlValidator.requireReadOnly(query)
        validateAdvancedOptions(body)

        val result = sqlExecClient.exec(
            endpointUrl = ctx.endpointUrl,
            bearer = ctx.bearer,
            query = query,
            asDict = body.asDict ?: true,
            withDescription = body.withDescription ?: true
        )

        val extraAttachments = mutableListOf<EmailAttachment>()
        var payloadForEmail = result

        if (body.attachPdf == true) {
            val pdfMode = normalizePdfMode(body.pdfMode)
            val pdfBytes = if (pdfMode == "scheduled_sql") {
                emailService.generateScheduledSqlPdf(
                    title = body.pdfTitle,
                    subtitle = body.pdfSubtitle,
                    includeSummary = body.pdfIncludeSummary ?: true,
                    maxRows = body.pdfMaxRows ?: 2000,
                    queryResult = result
                )
            } else {
                val reportId = requireNotNull(body.reportId) { "Para anexar PDF, informe 'reportId'" }
                reportService.generatePdf(reportId, ctx, ReportRunRequest())
            }
            val reportId = body.reportId
            extraAttachments += EmailAttachment(
                filename = if (pdfMode == "scheduled_sql") "agendamento-sql.pdf" else "report-$reportId.pdf",
                contentType = "application/pdf",
                bytes = pdfBytes
            )
        }

        if (body.compareWithSap == true) {
            val secondSql = body.secondSql?.trim().orEmpty()
            val sourceRows = (result["data"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            val hanaResult = hanaQueryService.exec(secondSql)
            val comparison = comparisonService.compareRows(
                sourceRows = sourceRows,
                source2Rows = hanaResult.rows,
                comparisonKey = body.comparisonKey,
                tolerances = body.comparisonTolerances
            )
            val sendOnlyIfDifferent = body.sendOnlyIfDifferent ?: true
            if (sendOnlyIfDifferent && !comparison.hasDifference) {
                return EmailSendResult(previewRows = 0, attachedXlsx = false, sent = false)
            }

            val comparisonWorkbook = EmailReportFormatter.buildComparisonWorkbook(
                source1Label = "agromobi",
                source1Rows = sourceRows,
                source2Label = "sap",
                source2Rows = hanaResult.rows,
                maxRowsPerSheet = 50_000
            )
            extraAttachments += EmailAttachment(
                filename = "comparacao-agromobi-sap.xlsx",
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes = comparisonWorkbook
            )

            payloadForEmail = mapOf(
                "data" to listOf(
                    mapOf(
                        "status" to if (comparison.hasDifference) "DIVERGENCIA" else "SEM_DIVERGENCIA",
                        "apenas_agromobi" to comparison.onlyInSource1,
                        "apenas_sap" to comparison.onlyInSource2,
                        "linhas_diferentes" to comparison.differentRows,
                        "linhas_correspondentes" to comparison.matchedRows
                    )
                )
            )
        }

        val includeTabularXlsx = body.attachXlsx ?: true
        val includeDefaultTableAttachment = includeTabularXlsx && body.compareWithSap != true
        return emailService.sendReport(
            request = body,
            queryResult = payloadForEmail,
            extraAttachments = if (includeTabularXlsx) extraAttachments else emptyList(),
            attachTabularXlsx = includeDefaultTableAttachment
        )
    }

    private val jobGroup = "email-report"

    fun schedule(body: EmailReportRequest, ctx: UpstreamContext, query: String): ScheduledReportResult {
        val scheduled = scheduleInternal(body, ctx, query)
        return ScheduledReportResult(id = scheduled.id, cron = scheduled.cron, nextRun = scheduled.nextRun?.let {
            ZonedDateTime.parse(it)
        })
    }

    fun scheduleDetailed(body: EmailReportRequest, ctx: UpstreamContext, query: String): EmailScheduleResponse =
        scheduleInternal(body, ctx, query)

    fun listSchedules(): List<EmailScheduleResponse> {
        val jobKeys = scheduler.getJobKeys(GroupMatcher.anyGroup())
        return jobKeys.mapNotNull { key ->
            val detail = scheduler.getJobDetail(key) ?: return@mapNotNull null
            if (detail.jobClass != EmailReportJob::class.java) return@mapNotNull null
            val trigger = scheduler.getTriggersOfJob(key).firstOrNull()
            toScheduleResponse(detail, trigger)
        }.sortedBy { it.nextRun ?: "" }
    }

    fun getSchedule(id: String): EmailScheduleResponse {
        val key = findJobKey(id) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        val detail = scheduler.getJobDetail(key) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        val trigger = scheduler.getTriggersOfJob(key).firstOrNull()
        return toScheduleResponse(detail, trigger)
            ?: throw IllegalArgumentException("Agendamento nao encontrado")
    }

    fun updateSchedule(id: String, body: EmailReportRequest, ctx: UpstreamContext, query: String): EmailScheduleResponse {
        ReadOnlySqlValidator.requireReadOnly(query)
        validateAdvancedOptions(body)
        val key = findJobKey(id) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        val (hour, minute) = cronService.parseTime(body.time)
        val days = cronService.parseDays(body.days)
        val cron = cronService.buildCron(hour, minute, days)

        val detail = scheduler.getJobDetail(key) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        detail.jobDataMap.apply {
            put("endpointUrl", ctx.endpointUrl)
            put("bearer", ctx.bearer)
            put("sql", query)
            put("to", body.to)
            put("cc", body.cc ?: "")
            put("subject", body.subject ?: "")
            put("asDict", body.asDict ?: true)
            put("withDescription", body.withDescription ?: true)
            put("attachXlsx", body.attachXlsx ?: true)
            put("message", body.message ?: "")
            put("reportId", body.reportId?.toString() ?: "")
            put("attachPdf", body.attachPdf ?: false)
            put("pdfMode", normalizePdfMode(body.pdfMode))
            put("pdfTitle", body.pdfTitle ?: "")
            put("pdfSubtitle", body.pdfSubtitle ?: "")
            put("pdfIncludeSummary", body.pdfIncludeSummary ?: true)
            put("pdfMaxRows", body.pdfMaxRows ?: 2000)
            put("compareWithSap", body.compareWithSap ?: false)
            put("comparisonTitle", body.comparisonTitle ?: "")
            put("comparisonNote", body.comparisonNote ?: "")
            put("secondSql", body.secondSql ?: "")
            put("comparisonKey", body.comparisonKey ?: "")
            put("comparisonTolerances", comparisonService.serializeTolerances(body.comparisonTolerances))
            put("sendOnlyIfDifferent", body.sendOnlyIfDifferent ?: true)
            put("time", body.time)
            put("days", days.joinToString(",") { it.name })
        }
        scheduler.addJob(detail, true, true)

        val triggerKey = triggerKeyFor(id, key.group)
        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKey)
            .forJob(detail)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(cron)
                    .inTimeZone(TimeZone.getTimeZone(cronService.scheduleZone))
            )
            .build()
        scheduler.rescheduleJob(triggerKey, trigger)

        return toScheduleResponse(detail, trigger)
            ?: throw IllegalArgumentException("Agendamento nao encontrado")
    }

    fun pauseSchedule(id: String) {
        val key = findJobKey(id) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        scheduler.pauseTrigger(triggerKeyFor(id, key.group))
    }

    fun resumeSchedule(id: String) {
        val key = findJobKey(id) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        scheduler.resumeTrigger(triggerKeyFor(id, key.group))
    }

    fun deleteSchedule(id: String) {
        val key = findJobKey(id) ?: throw IllegalArgumentException("Agendamento nao encontrado")
        scheduler.deleteJob(key)
    }

    private fun scheduleInternal(body: EmailReportRequest, ctx: UpstreamContext, query: String): EmailScheduleResponse {
        ReadOnlySqlValidator.requireReadOnly(query)
        validateAdvancedOptions(body)
        val (hour, minute) = cronService.parseTime(body.time)
        val days = cronService.parseDays(body.days)
        val cron = cronService.buildCron(hour, minute, days)
        val id = UUID.randomUUID().toString()

        val jobDetail = JobBuilder.newJob(EmailReportJob::class.java)
            .withIdentity(id, jobGroup)
            .usingJobData("endpointUrl", ctx.endpointUrl)
            .usingJobData("bearer", ctx.bearer)
            .usingJobData("sql", query)
            .usingJobData("to", body.to)
            .usingJobData("cc", body.cc ?: "")
            .usingJobData("subject", body.subject ?: "")
            .usingJobData("asDict", body.asDict ?: true)
            .usingJobData("withDescription", body.withDescription ?: true)
            .usingJobData("attachXlsx", body.attachXlsx ?: true)
            .usingJobData("message", body.message ?: "")
            .usingJobData("reportId", body.reportId?.toString() ?: "")
            .usingJobData("attachPdf", body.attachPdf ?: false)
            .usingJobData("pdfMode", normalizePdfMode(body.pdfMode))
            .usingJobData("pdfTitle", body.pdfTitle ?: "")
            .usingJobData("pdfSubtitle", body.pdfSubtitle ?: "")
            .usingJobData("pdfIncludeSummary", body.pdfIncludeSummary ?: true)
            .usingJobData("pdfMaxRows", body.pdfMaxRows ?: 2000)
            .usingJobData("compareWithSap", body.compareWithSap ?: false)
            .usingJobData("comparisonTitle", body.comparisonTitle ?: "")
            .usingJobData("comparisonNote", body.comparisonNote ?: "")
            .usingJobData("secondSql", body.secondSql ?: "")
            .usingJobData("comparisonKey", body.comparisonKey ?: "")
            .usingJobData("comparisonTolerances", comparisonService.serializeTolerances(body.comparisonTolerances))
            .usingJobData("sendOnlyIfDifferent", body.sendOnlyIfDifferent ?: true)
            .usingJobData("time", body.time)
            .usingJobData("days", days.joinToString(",") { it.name })
            .storeDurably()
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-$id", jobGroup)
            .forJob(jobDetail.key)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(cron)
                    .inTimeZone(TimeZone.getTimeZone(cronService.scheduleZone))
            )
            .build()

        scheduler.scheduleJob(jobDetail, trigger)

        val nextRunInstant = trigger.nextFireTime
        log.info("Scheduled email with quartz scheduleId={} cron={}", id, cron)
        return toScheduleResponse(jobDetail, trigger)
            ?: EmailScheduleResponse(
                id = id,
                cron = cron,
                nextRun = nextRunInstant?.toInstant()?.atZone(cronService.scheduleZone)?.toString(),
                status = "NORMAL",
                time = body.time ?: "",
                days = days.map { it.name },
                sql = query,
                to = body.to,
                cc = body.cc,
                subject = body.subject,
                asDict = body.asDict ?: true,
                withDescription = body.withDescription ?: true,
                attachXlsx = body.attachXlsx ?: true,
                message = body.message,
                reportId = body.reportId,
                attachPdf = body.attachPdf ?: false,
                pdfMode = normalizePdfMode(body.pdfMode),
                pdfTitle = body.pdfTitle,
                pdfSubtitle = body.pdfSubtitle,
                pdfIncludeSummary = body.pdfIncludeSummary ?: true,
                pdfMaxRows = body.pdfMaxRows ?: 2000,
                compareWithSap = body.compareWithSap ?: false,
                comparisonTitle = body.comparisonTitle,
                comparisonNote = body.comparisonNote,
                secondSql = body.secondSql,
                comparisonKey = body.comparisonKey,
                comparisonTolerances = body.comparisonTolerances,
                sendOnlyIfDifferent = body.sendOnlyIfDifferent ?: true
            )
    }

    private fun validateAdvancedOptions(body: EmailReportRequest) {
        if (body.attachPdf == true) {
            val mode = normalizePdfMode(body.pdfMode)
            if (mode == "report") {
                require(body.reportId != null) { "Para anexar PDF com mode=report, informe 'reportId'" }
            }
            require((body.pdfMaxRows ?: 2000) > 0) { "'pdfMaxRows' deve ser maior que zero" }
        }
        if (body.compareWithSap == true) {
            val secondSql = body.secondSql?.trim()
            require(!secondSql.isNullOrBlank()) { "Para comparar com SAP, informe 'secondSql'" }
            ReadOnlySqlValidator.requireReadOnly(secondSql)
        }
    }

    private fun findJobKey(id: String): JobKey? {
        val keys = scheduler.getJobKeys(GroupMatcher.anyGroup())
        return keys.firstOrNull { key ->
            key.name == id && scheduler.getJobDetail(key)?.jobClass == EmailReportJob::class.java
        }
    }

    private fun triggerKeyFor(id: String, group: String) = TriggerKey("trigger-$id", group)

    private fun toScheduleResponse(detail: org.quartz.JobDetail, trigger: Trigger?): EmailScheduleResponse? {
        val data = detail.jobDataMap
        val cron = (trigger as? org.quartz.CronTrigger)?.cronExpression ?: ""
        val nextRun = trigger?.nextFireTime?.toInstant()?.atZone(cronService.scheduleZone)?.toString()
        val status = trigger?.let { scheduler.getTriggerState(it.key).name } ?: "NONE"
        return EmailScheduleResponse(
            id = detail.key.name,
            cron = cron,
            nextRun = nextRun,
            status = status,
            time = data.getString("time") ?: "",
            days = data.getString("days")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            sql = data.getString("sql") ?: "",
            to = data.getString("to") ?: "",
            cc = data.getString("cc"),
            subject = data.getString("subject"),
            asDict = data.getBooleanValue("asDict"),
            withDescription = data.getBooleanValue("withDescription"),
            attachXlsx = if (data.containsKey("attachXlsx")) data.getBooleanValue("attachXlsx") else true,
            message = data.getString("message")?.takeIf { it.isNotBlank() },
            reportId = data.getString("reportId")?.trim()?.takeIf { it.isNotBlank() }?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            attachPdf = data.getBooleanValue("attachPdf"),
            pdfMode = normalizePdfMode(data.getString("pdfMode")),
            pdfTitle = data.getString("pdfTitle")?.takeIf { it.isNotBlank() },
            pdfSubtitle = data.getString("pdfSubtitle")?.takeIf { it.isNotBlank() },
            pdfIncludeSummary = if (data.containsKey("pdfIncludeSummary")) data.getBooleanValue("pdfIncludeSummary") else true,
            pdfMaxRows = if (data.containsKey("pdfMaxRows")) data.getIntValue("pdfMaxRows") else 2000,
            compareWithSap = data.getBooleanValue("compareWithSap"),
            comparisonTitle = data.getString("comparisonTitle")?.takeIf { it.isNotBlank() },
            comparisonNote = data.getString("comparisonNote")?.takeIf { it.isNotBlank() },
            secondSql = data.getString("secondSql")?.takeIf { it.isNotBlank() },
            comparisonKey = data.getString("comparisonKey")?.takeIf { it.isNotBlank() },
            comparisonTolerances = comparisonService.parseTolerances(data.getString("comparisonTolerances")),
            sendOnlyIfDifferent = if (data.containsKey("sendOnlyIfDifferent")) data.getBooleanValue("sendOnlyIfDifferent") else true
        )
    }

    private fun normalizePdfMode(raw: String?): String {
        val mode = raw?.trim()?.lowercase()
        return if (mode == "scheduled_sql") "scheduled_sql" else "report"
    }

}
