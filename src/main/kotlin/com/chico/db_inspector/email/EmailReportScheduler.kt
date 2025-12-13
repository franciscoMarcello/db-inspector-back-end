package com.chico.dbinspector.email

import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.quartz.CronScheduleBuilder
import org.quartz.CronExpression
import org.quartz.JobBuilder
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
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
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val log = LoggerFactory.getLogger(EmailReportScheduler::class.java)
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

    fun schedule(body: EmailReportRequest, ctx: UpstreamContext, query: String): ScheduledReportResult {
        val (hour, minute) = parseTime(body.time)
        val days = parseDays(body.days)
        val cron = buildCron(hour, minute, days)
        val id = UUID.randomUUID().toString()

        val jobDetail = JobBuilder.newJob(EmailReportJob::class.java)
            .withIdentity(id)
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
            .withIdentity("trigger-$id")
            .forJob(jobDetail)
            .withSchedule(
                CronScheduleBuilder.cronSchedule(cron)
                    .inTimeZone(TimeZone.getTimeZone(clock.zone))
            )
            .build()

        scheduler.scheduleJob(jobDetail, trigger)

        val nextRunInstant = CronExpression.parse(cron).next(ZonedDateTime.now(clock))
        log.info("Scheduled email with quartz scheduleId={} cron={}", id, cron)
        return ScheduledReportResult(id = id, cron = cron, nextRun = nextRunInstant?.withZoneSameInstant(clock.zone))
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
}
