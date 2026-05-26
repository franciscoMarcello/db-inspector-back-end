package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
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
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.ZoneId
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
    private val hanaQueryService: HanaQueryService,
    private val reportService: ReportService,
    private val properties: DbInspectorProperties
) {
    private val log = LoggerFactory.getLogger(EmailReportScheduler::class.java)
    private val scheduleZone: ZoneId = runCatching { ZoneId.of(properties.schedule.timeZone.trim()) }
        .getOrElse {
            log.warn(
                "Timezone invalido em dbinspector.schedule.time-zone='{}'. Usando America/Porto_Velho",
                properties.schedule.timeZone
            )
            ZoneId.of("America/Porto_Velho")
        }
    private val dayOfWeekAlias = mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY
    )

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
            val comparison = compareRows(
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
        val (hour, minute) = parseTime(body.time)
        val days = parseDays(body.days)
        val cron = buildCron(hour, minute, days)

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
            put("comparisonTolerances", serializeComparisonTolerances(body.comparisonTolerances))
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
                    .inTimeZone(TimeZone.getTimeZone(scheduleZone))
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
        val (hour, minute) = parseTime(body.time)
        val days = parseDays(body.days)
        val cron = buildCron(hour, minute, days)
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
            .usingJobData("comparisonTolerances", serializeComparisonTolerances(body.comparisonTolerances))
            .usingJobData("sendOnlyIfDifferent", body.sendOnlyIfDifferent ?: true)
            .usingJobData("time", body.time)
            .usingJobData("days", days.joinToString(",") { it.name })
            .storeDurably()
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity("trigger-$id", jobGroup)
            .forJob(jobDetail)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(cron)
                    .inTimeZone(TimeZone.getTimeZone(scheduleZone))
            )
            .build()

        scheduler.scheduleJob(jobDetail, trigger)

        val nextRunInstant = trigger.nextFireTime
        log.info("Scheduled email with quartz scheduleId={} cron={}", id, cron)
        return toScheduleResponse(jobDetail, trigger)
            ?: EmailScheduleResponse(
                id = id,
                cron = cron,
                nextRun = nextRunInstant?.toInstant()?.atZone(scheduleZone)?.toString(),
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

    private fun parseTime(raw: String?): Pair<Int, Int> {
        val time = raw?.trim() ?: throw IllegalArgumentException("Para agendar, informe 'time' no formato HH:mm")
        require(time.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) { "Formato invalido para 'time' (use HH:mm, ex.: 08:00)" }
        val (hourStr, minuteStr) = time.split(":")
        return hourStr.toInt() to minuteStr.toInt()
    }

    private fun parseDays(rawDays: List<String>?): List<DayOfWeek> {
        val days = rawDays?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Para agendar, informe pelo menos um dia em 'days'")
        require(days.isNotEmpty()) { "Para agendar, informe pelo menos um dia em 'days'" }

        return days.map { alias ->
            dayOfWeekAlias[alias] ?: throw IllegalArgumentException("Dia invalido em 'days': $alias (use mon,tue,wed,thu,fri,sat,sun)")
        }.distinct()
    }

    private fun buildCron(hour: Int, minute: Int, days: List<DayOfWeek>): String {
        require(days.isNotEmpty()) { "Para agendar, informe pelo menos um dia em 'days'" }
        val dow = days.joinToString(",") { it.name.take(3) }
        return "0 $minute $hour ? * $dow"
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
        val nextRun = trigger?.nextFireTime?.toInstant()?.atZone(scheduleZone)?.toString()
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
            comparisonTolerances = parseComparisonTolerances(data.getString("comparisonTolerances")),
            sendOnlyIfDifferent = if (data.containsKey("sendOnlyIfDifferent")) data.getBooleanValue("sendOnlyIfDifferent") else true
        )
    }

    private fun serializeComparisonTolerances(tolerances: Map<String, Double>): String =
        tolerances.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun parseComparisonTolerances(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.lastIndex) return@mapNotNull null
                val key = entry.substring(0, idx).trim()
                val value = entry.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                if (key.isBlank()) return@mapNotNull null
                key to value
            }
            .toMap()
    }

    private fun normalizePdfMode(raw: String?): String {
        val mode = raw?.trim()?.lowercase()
        return if (mode == "scheduled_sql") "scheduled_sql" else "report"
    }

    private fun compareRows(
        sourceRows: List<Map<String, Any?>>,
        source2Rows: List<Map<String, Any?>>,
        comparisonKey: String?,
        tolerances: Map<String, Double>
    ): ComparisonStats {
        val toleranceByField = tolerances.mapKeys { it.key.lowercase() }.mapValues { BigDecimal.valueOf(it.value) }
        return if (comparisonKey.isNullOrBlank()) {
            val remaining = source2Rows.toMutableList()
            var differentRows = 0
            var matchedRows = 0
            sourceRows.forEach { row1 ->
                val idx = remaining.indexOfFirst { row2 -> rowsEqualWithTolerance(row1, row2, toleranceByField) }
                if (idx >= 0) {
                    matchedRows++
                    remaining.removeAt(idx)
                } else {
                    differentRows++
                }
            }
            ComparisonStats(
                hasDifference = differentRows > 0 || remaining.isNotEmpty(),
                onlyInSource1 = differentRows,
                onlyInSource2 = remaining.size,
                differentRows = differentRows + remaining.size,
                matchedRows = matchedRows
            )
        } else {
            val rows1ByKey = sourceRows.groupBy { normalizeKey(it[comparisonKey]) }
            val rows2ByKey = source2Rows.groupBy { normalizeKey(it[comparisonKey]) }
            val allKeys = (rows1ByKey.keys + rows2ByKey.keys).distinct()
            var onlyInSource1 = 0
            var onlyInSource2 = 0
            var differentRows = 0
            var matchedRows = 0
            allKeys.forEach { key ->
                val l1 = rows1ByKey[key] ?: emptyList()
                val l2 = rows2ByKey[key] ?: emptyList()
                val matched = minOf(l1.size, l2.size)
                if (l1.size > matched) onlyInSource1 += l1.size - matched
                if (l2.size > matched) onlyInSource2 += l2.size - matched
                repeat(matched) { idx ->
                    if (rowsEqualWithTolerance(l1[idx], l2[idx], toleranceByField)) {
                        matchedRows++
                    } else {
                        differentRows++
                    }
                }
            }
            ComparisonStats(
                hasDifference = onlyInSource1 > 0 || onlyInSource2 > 0 || differentRows > 0,
                onlyInSource1 = onlyInSource1,
                onlyInSource2 = onlyInSource2,
                differentRows = differentRows,
                matchedRows = matchedRows
            )
        }
    }

    private fun rowsEqualWithTolerance(
        row1: Map<String, Any?>,
        row2: Map<String, Any?>,
        tolerances: Map<String, BigDecimal>
    ): Boolean {
        val allColumns = (row1.keys + row2.keys).toSet()
        return allColumns.all { col ->
            val tolerance = tolerances[col.lowercase()]
            valuesEqual(row1[col], row2[col], tolerance)
        }
    }

    private fun valuesEqual(v1: Any?, v2: Any?, tolerance: BigDecimal?): Boolean {
        if (v1 == null && v2 == null) return true
        if (v1 == null || v2 == null) return false
        val bd1 = runCatching { BigDecimal(v1.toString().trim()) }.getOrNull()
        val bd2 = runCatching { BigDecimal(v2.toString().trim()) }.getOrNull()
        if (bd1 != null && bd2 != null) {
            val delta = (bd1 - bd2).abs()
            return tolerance?.let { delta <= it } ?: (bd1.compareTo(bd2) == 0)
        }
        return v1.toString().trim().lowercase() == v2.toString().trim().lowercase()
    }

    private fun normalizeKey(value: Any?): String? {
        if (value == null) return null
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        val numeric = runCatching { BigDecimal(text) }.getOrNull()
        return numeric?.stripTrailingZeros()?.toPlainString() ?: text.lowercase()
    }

    private data class ComparisonStats(
        val hasDifference: Boolean,
        val onlyInSource1: Int,
        val onlyInSource2: Int,
        val differentRows: Int,
        val matchedRows: Int
    )
}
