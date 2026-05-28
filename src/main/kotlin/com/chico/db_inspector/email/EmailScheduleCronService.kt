package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.ZoneId

@Service
class EmailScheduleCronService(
    properties: DbInspectorProperties
) {
    private val log = LoggerFactory.getLogger(EmailScheduleCronService::class.java)
    private val dayOfWeekAlias = mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY
    )

    val scheduleZone: ZoneId = runCatching { ZoneId.of(properties.schedule.timeZone.trim()) }
        .getOrElse {
            log.warn(
                "Timezone invalido em dbinspector.schedule.time-zone='{}'. Usando America/Porto_Velho",
                properties.schedule.timeZone
            )
            ZoneId.of("America/Porto_Velho")
        }

    fun parseTime(raw: String?): Pair<Int, Int> {
        val time = raw?.trim() ?: throw IllegalArgumentException("Para agendar, informe 'time' no formato HH:mm")
        require(time.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))) { "Formato invalido para 'time' (use HH:mm, ex.: 08:00)" }
        val (hourStr, minuteStr) = time.split(":")
        return hourStr.toInt() to minuteStr.toInt()
    }

    fun parseDays(rawDays: List<String>?): List<DayOfWeek> {
        val days = rawDays?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Para agendar, informe pelo menos um dia em 'days'")
        require(days.isNotEmpty()) { "Para agendar, informe pelo menos um dia em 'days'" }

        return days.map { alias ->
            dayOfWeekAlias[alias] ?: throw IllegalArgumentException("Dia invalido em 'days': $alias (use mon,tue,wed,thu,fri,sat,sun)")
        }.distinct()
    }

    fun buildCron(hour: Int, minute: Int, days: List<DayOfWeek>): String {
        require(days.isNotEmpty()) { "Para agendar, informe pelo menos um dia em 'days'" }
        val dow = days.joinToString(",") { it.name.take(3) }
        return "0 $minute $hour ? * $dow"
    }
}
