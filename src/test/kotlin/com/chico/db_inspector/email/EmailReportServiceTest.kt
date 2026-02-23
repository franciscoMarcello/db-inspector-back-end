package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties

class EmailReportServiceTest {

    @Test
    fun `sendReport should skip sending when result has no data rows`() {
        val mailSender = Mockito.mock(JavaMailSender::class.java)
        val service = EmailReportService(
            mailSender = mailSender,
            properties = DbInspectorProperties(),
            mapper = ObjectMapper()
        )

        val result = service.sendReport(
            request = EmailReportRequest(sql = "select 1", to = "dev@example.com"),
            queryResult = mapOf("data" to emptyList<Map<String, Any?>>())
        )

        assertFalse(result.sent)
        assertEquals(0, result.previewRows)
        assertFalse(result.attachedXlsx)
        Mockito.verify(mailSender, Mockito.never()).createMimeMessage()
        Mockito.verify(mailSender, Mockito.never()).send(Mockito.any(MimeMessage::class.java))
    }

    @Test
    fun `sendReport should send email when result has data rows`() {
        val mailSender = Mockito.mock(JavaMailSender::class.java)
        Mockito.`when`(mailSender.createMimeMessage())
            .thenReturn(MimeMessage(Session.getInstance(Properties())))

        val service = EmailReportService(
            mailSender = mailSender,
            properties = DbInspectorProperties(),
            mapper = ObjectMapper()
        )

        val result = service.sendReport(
            request = EmailReportRequest(sql = "select 1", to = "dev@example.com"),
            queryResult = mapOf("data" to listOf(mapOf("id" to 1, "name" to "abc")))
        )

        assertTrue(result.sent)
        assertEquals(1, result.previewRows)
        Mockito.verify(mailSender).createMimeMessage()
        Mockito.verify(mailSender).send(Mockito.any(MimeMessage::class.java))
    }
}
