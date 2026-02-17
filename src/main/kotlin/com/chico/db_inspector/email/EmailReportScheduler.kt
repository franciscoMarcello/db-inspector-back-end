package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
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

    fun sendNow(body: EmailReportRequest, ctx: UpstreamContext, query: String) =
        sqlExecClient.exec(
            endpointUrl = ctx.endpointUrl,
            bearer = ctx.bearer,
            query = query,
            asDict = body.asDict ?: true,
            withDescription = body.withDescription ?: true
        ).let { emailService.sendReport(body, it) }

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
                withDescription = body.withDescription ?: true
            )
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
            withDescription = data.getBooleanValue("withDescription")
        )
    }
}
