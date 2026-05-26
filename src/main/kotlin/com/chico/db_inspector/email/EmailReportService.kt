package com.chico.dbinspector.email

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperExportManager
import net.sf.jasperreports.engine.JasperFillManager
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class EmailSendResult(
    val previewRows: Int,
    val attachedXlsx: Boolean,
    val sent: Boolean
)

data class EmailAttachment(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray
)

data class ComparisonEmailSummary(
    val status: String,
    val apenasAgromobi: Int,
    val apenasSap: Int,
    val linhasDiferentes: Int,
    val linhasCorrespondentes: Int
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

    fun generateScheduledSqlPdf(
        title: String?,
        subtitle: String?,
        includeSummary: Boolean,
        maxRows: Int,
        queryResult: Map<String, Any?>
    ): ByteArray {
        val tabular = EmailReportFormatter.toTabular(queryResult)
            ?: throw IllegalArgumentException("Resultado da consulta sem dados para PDF")
        val safeMaxRows = maxRows.coerceIn(1, 20_000)
        val columns = tabular.columns.take(8)
        val rows = tabular.rows.take(safeMaxRows).map { rowValues ->
            columns.indices.associate { idx -> "c$idx" to (rowValues.getOrNull(idx)?.toString() ?: "") }
        }

        val resolvedTitle = title?.trim().takeUnless { it.isNullOrBlank() } ?: "Relatorio SQL Agendado"
        val resolvedSubtitle = subtitle?.trim().takeUnless { it.isNullOrBlank() }
        val summaryText = if (includeSummary) "Linhas: ${tabular.rows.size} | Colunas: ${tabular.columns.size}" else ""

        val pageWidth = 555
        val colWidth = (pageWidth / columns.size.coerceAtLeast(1)).coerceAtLeast(70)
        val declaredFields = columns.indices.joinToString("\n") { idx ->
            "    <field name=\"c$idx\" class=\"java.lang.String\"/>"
        }
        val headerCells = columns.mapIndexed { idx, name ->
            val x = idx * colWidth
            """
            <staticText>
                <reportElement x=\"$x\" y=\"0\" width=\"$colWidth\" height=\"20\" backcolor=\"#F3F4F6\" mode=\"Opaque\"/>
                <textElement verticalAlignment=\"Middle\"><font size=\"10\" isBold=\"true\"/></textElement>
                <text><![CDATA[$name]]></text>
            </staticText>
            """.trimIndent()
        }.joinToString("\n")
        val detailCells = columns.indices.joinToString("\n") { idx ->
            val x = idx * colWidth
            """
            <textField isStretchWithOverflow=\"true\">
                <reportElement x=\"$x\" y=\"0\" width=\"$colWidth\" height=\"18\"/>
                <textElement verticalAlignment=\"Middle\"><font size=\"9\"/></textElement>
                <textFieldExpression><![CDATA[${'$'}F{c$idx}]]></textFieldExpression>
            </textField>
            """.trimIndent()
        }

        val subtitleBand = resolvedSubtitle?.let {
            """
            <textField>
                <reportElement x=\"0\" y=\"28\" width=\"555\" height=\"16\"/>
                <textElement><font size=\"11\"/></textElement>
                <textFieldExpression><![CDATA[${'$'}P{subtitle}]]></textFieldExpression>
            </textField>
            """.trimIndent()
        } ?: ""

        val summaryBand = if (includeSummary) {
            """
            <textField>
                <reportElement x=\"0\" y=\"46\" width=\"555\" height=\"14\"/>
                <textElement><font size=\"10\"/></textElement>
                <textFieldExpression><![CDATA[${'$'}P{summary}]]></textFieldExpression>
            </textField>
            """.trimIndent()
        } else ""

        val jrxml = """
            <?xml version=\"1.0\" encoding=\"UTF-8\"?>
            <jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"
                xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\"
                name=\"scheduled_sql\" pageWidth=\"595\" pageHeight=\"842\" columnWidth=\"555\"
                leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\">
                <parameter name=\"title\" class=\"java.lang.String\"/>
                <parameter name=\"subtitle\" class=\"java.lang.String\"/>
                <parameter name=\"summary\" class=\"java.lang.String\"/>
            $declaredFields
                <title>
                    <band height=\"64\">
                        <textField>
                            <reportElement x=\"0\" y=\"0\" width=\"555\" height=\"26\"/>
                            <textElement><font size=\"16\" isBold=\"true\"/></textElement>
                            <textFieldExpression><![CDATA[${'$'}P{title}]]></textFieldExpression>
                        </textField>
            $subtitleBand
            $summaryBand
                    </band>
                </title>
                <columnHeader>
                    <band height=\"20\">$headerCells</band>
                </columnHeader>
                <detail>
                    <band height=\"18\">$detailCells</band>
                </detail>
            </jasperReport>
        """.trimIndent()

        val report = ByteArrayInputStream(jrxml.toByteArray(StandardCharsets.UTF_8)).use { input ->
            JasperCompileManager.compileReport(input)
        }
        val params = mapOf(
            "title" to resolvedTitle,
            "subtitle" to (resolvedSubtitle ?: ""),
            "summary" to summaryText
        )
        val print = JasperFillManager.fillReport(report, params, JRMapCollectionDataSource(rows))
        return JasperExportManager.exportReportToPdf(print)
    }

    fun sendReport(
        request: EmailReportRequest,
        queryResult: Map<String, Any?>,
        extraAttachments: List<EmailAttachment> = emptyList(),
        attachTabularXlsx: Boolean = true,
        scheduleId: String? = null
    ): EmailSendResult {
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
        val summary = extractComparisonSummary(queryResult)
        val templateVars = buildTemplateVariables(summary, scheduleId)
        val renderedMessage = renderTemplate(request.message, templateVars)
        val htmlBody = if (summary != null) {
            EmailReportFormatter.buildExecutiveComparisonHtml(
                summary = summary,
                title = request.comparisonTitle,
                note = request.comparisonNote
            )
        } else {
            EmailReportFormatter.buildHtmlPreview(tabular, properties.schedule.previewLimit, prettyJson)
        }
        val html = buildHtmlWithMessage(renderedMessage, htmlBody)
        val xlsxBytes = tabular?.let { EmailReportFormatter.buildXlsx(it, properties.schedule.attachmentRowLimit) }

        val attachXlsx = attachTabularXlsx && xlsxBytes != null && xlsxBytes.size <= properties.schedule.attachmentSizeLimitBytes
        val attachments = mutableListOf<EmailAttachment>()
        if (attachXlsx && xlsxBytes != null) {
            attachments += EmailAttachment(
                filename = "report.xlsx",
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes = xlsxBytes
            )
        }
        attachments += extraAttachments

        val hasAttachment = attachments.isNotEmpty()
        val subject = request.subject?.takeIf { it.isNotBlank() }
            ?: "DB Inspector Report - ${LocalDateTime.now().format(subjectFmt)}"

        val mimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mimeMessage, hasAttachment, "UTF-8")
        helper.setFrom(properties.mail.from)
        helper.setTo(to.toTypedArray())
        if (cc.isNotEmpty()) helper.setCc(cc.toTypedArray())
        helper.setSubject(subject)
        helper.setText(html, true)

        attachments.forEach { attachment ->
            helper.addAttachment(
                attachment.filename,
                ByteArrayResource(attachment.bytes),
                attachment.contentType
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

    private fun buildHtmlWithMessage(message: String?, htmlBody: String): String {
        val customMessage = message?.trim().takeUnless { it.isNullOrBlank() } ?: return htmlBody
        val escaped = customMessage
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br/>")
        return """
            <div style="font-family: Arial, sans-serif; font-size:12px; margin-bottom:12px;">
              <p><strong>Mensagem:</strong></p>
              <p>$escaped</p>
            </div>
            $htmlBody
        """.trimIndent()
    }

    private fun extractComparisonSummary(queryResult: Map<String, Any?>): ComparisonEmailSummary? {
        val row = (queryResult["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val status = row["status"]?.toString() ?: return null
        val apenasAgromobi = (row["apenas_agromobi"] as? Number)?.toInt() ?: return null
        val apenasSap = (row["apenas_sap"] as? Number)?.toInt() ?: return null
        val linhasDiferentes = (row["linhas_diferentes"] as? Number)?.toInt() ?: return null
        val linhasCorrespondentes = (row["linhas_correspondentes"] as? Number)?.toInt() ?: return null
        return ComparisonEmailSummary(status, apenasAgromobi, apenasSap, linhasDiferentes, linhasCorrespondentes)
    }

    private fun buildTemplateVariables(summary: ComparisonEmailSummary?, scheduleId: String?): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        vars["data_execucao"] = LocalDateTime.now().format(subjectFmt)
        vars["schedule_id"] = scheduleId ?: ""
        if (summary != null) {
            vars["status"] = summary.status
            vars["apenas_agromobi"] = summary.apenasAgromobi.toString()
            vars["apenas_sap"] = summary.apenasSap.toString()
            vars["linhas_diferentes"] = summary.linhasDiferentes.toString()
            vars["linhas_correspondentes"] = summary.linhasCorrespondentes.toString()
        }
        return vars
    }

    private fun renderTemplate(template: String?, variables: Map<String, String>): String? {
        if (template.isNullOrBlank()) return template
        var rendered = template
        rendered = Regex("\\{\\{([a-zA-Z0-9_]+)\\}\\}").replace(rendered) { match ->
            variables[match.groupValues[1]] ?: ""
        }
        rendered = Regex("\\{([a-zA-Z0-9_]+)\\}").replace(rendered) { match ->
            variables[match.groupValues[1]] ?: ""
        }
        return rendered
    }
}
