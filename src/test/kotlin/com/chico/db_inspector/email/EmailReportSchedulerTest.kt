package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.quartz.CronTrigger
import org.quartz.JobDetail
import org.quartz.Scheduler
import org.quartz.Trigger
import org.quartz.TriggerKey
import org.springframework.http.HttpStatus
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class EmailReportSchedulerTest {

    @Test
    fun `scheduleDetailed should create cron and apply America Porto Velho timezone`() {
        val schedulerMock = Mockito.mock(Scheduler::class.java)
        Mockito.`when`(schedulerMock.getTriggerState(Mockito.any(TriggerKey::class.java)))
            .thenReturn(Trigger.TriggerState.NORMAL)
        val scheduler = createScheduler("America/Porto_Velho", schedulerMock)

        val response = scheduler.scheduleDetailed(
            body = EmailReportRequest(
                sql = "select 1",
                to = "dev@example.com",
                time = "08:00",
                days = listOf("mon", "wed")
            ),
            ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer token"),
            query = "select 1"
        )

        assertEquals("0 0 8 ? * MON,WED", response.cron)
        assertEquals("NORMAL", response.status)

        val triggerCaptor = ArgumentCaptor.forClass(Trigger::class.java)
        Mockito.verify(schedulerMock).scheduleJob(Mockito.any(JobDetail::class.java), triggerCaptor.capture())
        val cronTrigger = triggerCaptor.value as CronTrigger
        assertEquals("America/Porto_Velho", cronTrigger.timeZone.id)
    }

    @Test
    fun `scheduleDetailed should reject invalid day alias`() {
        val scheduler = createScheduler("America/Porto_Velho", Mockito.mock(Scheduler::class.java))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            scheduler.scheduleDetailed(
                body = EmailReportRequest(
                    sql = "select 1",
                    to = "dev@example.com",
                    time = "08:00",
                    days = listOf("monday")
                ),
                ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer token"),
                query = "select 1"
            )
        }

        assertEquals("Dia invalido em 'days': monday (use mon,tue,wed,thu,fri,sat,sun)", ex.message)
    }

    @Test
    fun `scheduler should fallback to America Porto Velho when timezone is invalid`() {
        val schedulerMock = Mockito.mock(Scheduler::class.java)
        Mockito.`when`(schedulerMock.getTriggerState(Mockito.any(TriggerKey::class.java)))
            .thenReturn(Trigger.TriggerState.NORMAL)
        val scheduler = createScheduler("Invalid/Zone", schedulerMock)

        scheduler.scheduleDetailed(
            body = EmailReportRequest(
                sql = "select 1",
                to = "dev@example.com",
                time = "08:00",
                days = listOf("fri")
            ),
            ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer token"),
            query = "select 1"
        )

        val triggerCaptor = ArgumentCaptor.forClass(Trigger::class.java)
        Mockito.verify(schedulerMock).scheduleJob(Mockito.any(JobDetail::class.java), triggerCaptor.capture())
        val cronTrigger = triggerCaptor.value as CronTrigger
        assertEquals("America/Porto_Velho", cronTrigger.timeZone.id)
    }

    private fun createScheduler(timeZone: String, scheduler: Scheduler): EmailReportScheduler {
        val properties = DbInspectorProperties(
            schedule = DbInspectorProperties.ScheduleProperties(timeZone = timeZone)
        )
        val sqlExecClient = SqlExecClient(
            WebClient.builder()
                .exchangeFunction(ExchangeFunction { Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()) })
                .build()
        )
        val emailService = EmailReportService(
            mailSender = Mockito.mock(JavaMailSender::class.java),
            properties = DbInspectorProperties(),
            mapper = ObjectMapper()
        )

        return EmailReportScheduler(
            scheduler = scheduler,
            sqlExecClient = sqlExecClient,
            emailService = emailService,
            properties = properties
        )
    }
}
