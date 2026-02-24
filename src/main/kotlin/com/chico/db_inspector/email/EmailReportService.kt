package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class EmailSendResult(
    val previewRows: Int,
    val attachedXlsx: Boolean,
    val sent: Boolean
)

@Service
class EmailReportService(
    private val mailSender: JavaMailSender,
    private val properties: DbInspectorProperties,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(EmailReportService::class.java)
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val subjectFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun sendReport(request: EmailReportRequest, queryResult: Map<String, Any?>): EmailSendResult {
        val to = parseEmails(request.to)
        val cc = parseEmails(request.cc)
        require(to.isNotEmpty()) { "Campo 'to' sem emails válidos" }
        if (!hasDataRows(queryResult)) {
            log.info("Email report skipped to={}, cc={} reason=no_data_rows", to, cc)
            return EmailSendResult(
                previewRows = 0,
                attachedXlsx = false,
                sent = false
            )
        }

        val tabular = EmailReportFormatter.toTabular(queryResult)
        val prettyJson = EmailReportFormatter.prettyJson(mapper, queryResult)
        val html = EmailReportFormatter.buildHtmlPreview(tabular, properties.schedule.previewLimit, prettyJson)
        val xlsxBytes = tabular?.let { EmailReportFormatter.buildXlsx(it, properties.schedule.attachmentRowLimit) }

        val attachXlsx = xlsxBytes != null && xlsxBytes.size <= properties.schedule.attachmentSizeLimitBytes
        val subject = request.subject?.takeIf { it.isNotBlank() }
            ?: "DB Inspector Report - ${LocalDateTime.now().format(subjectFmt)}"

        val mimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mimeMessage, attachXlsx, "UTF-8")
        helper.setFrom(properties.mail.from)
        helper.setTo(to.toTypedArray())
        if (cc.isNotEmpty()) helper.setCc(cc.toTypedArray())
        helper.setSubject(subject)
        helper.setText(html, true)

        if (attachXlsx && xlsxBytes != null) {
            helper.addAttachment(
                "report.xlsx",
                ByteArrayResource(xlsxBytes),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        }
        val previewRows = tabular?.rows?.size?.coerceAtMost(properties.schedule.previewLimit) ?: 0
        mailSender.send(mimeMessage)
        log.info(
            "Email report sent to={}, cc={}, previewRows={}, attachedXlsx={}",
            to, cc, previewRows, attachXlsx
        )

        return EmailSendResult(
            previewRows = previewRows,
            attachedXlsx = attachXlsx,
            sent = true
        )
    }

    fun sendTestEmail(request: EmailTestRequest) {
        val to = parseEmails(request.to)
        val cc = parseEmails(request.cc)
        require(to.isNotEmpty()) { "Campo 'to' sem emails validos" }

        val subject = request.subject?.takeIf { it.isNotBlank() }
            ?: "DB Inspector Test Email - ${LocalDateTime.now().format(subjectFmt)}"
        val body = request.message?.takeIf { it.isNotBlank() }
            ?: "Test email sent at ${LocalDateTime.now().format(subjectFmt)}"

        val mimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mimeMessage, false, "UTF-8")
        helper.setFrom(properties.mail.from)
        helper.setTo(to.toTypedArray())
        if (cc.isNotEmpty()) helper.setCc(cc.toTypedArray())
        helper.setSubject(subject)
        helper.setText(body, false)
        mailSender.send(mimeMessage)
        log.info("Test email sent to={}, cc={}", to, cc)
    }

    private fun parseEmails(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { emailRegex.matches(it) }
    }

    private fun hasDataRows(queryResult: Map<String, Any?>): Boolean {
        val data = queryResult["data"] as? List<*> ?: return false
        return data.any { row ->
            when (row) {
                is Map<*, *> -> row.isNotEmpty()
                is List<*> -> row.isNotEmpty()
                null -> false
                else -> true
            }
        }
    }
}
