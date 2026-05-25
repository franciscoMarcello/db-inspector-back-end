package com.chico.dbinspector.email

import com.chico.dbinspector.report.ReportRunRequest
import com.chico.dbinspector.report.ReportService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Component
class EmailReportJob(
    private val sqlExecClient: SqlExecClient,
    private val emailService: EmailReportService,
    private val hanaQueryService: HanaQueryService,
    private val reportService: ReportService
) : Job {
    private val log = LoggerFactory.getLogger(EmailReportJob::class.java)

    override fun execute(context: JobExecutionContext) {
        val data = context.mergedJobDataMap

        val endpointUrl = data.getString("endpointUrl")
        val bearer = data.getString("bearer")
        val sql = data.getString("sql")
        val to = data.getString("to")
        val cc = data.getString("cc")
        val subject = data.getString("subject")
        val asDict = data.getBooleanValue("asDict")
        val withDescription = data.getBooleanValue("withDescription")
        val message = data.getString("message")
        val reportId = data.getString("reportId")?.trim()?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
        val attachPdf = data.getBooleanValue("attachPdf")
        val compareWithSap = data.getBooleanValue("compareWithSap")
        val secondSql = data.getString("secondSql")?.trim().takeUnless { it.isNullOrBlank() }
        val comparisonKey = data.getString("comparisonKey")?.trim().takeUnless { it.isNullOrBlank() }
        val comparisonTolerances = parseComparisonTolerances(data.getString("comparisonTolerances"))
        val sendOnlyIfDifferent = if (data.containsKey("sendOnlyIfDifferent")) data.getBooleanValue("sendOnlyIfDifferent") else true
        val days = data.getString("days")?.split(",") ?: emptyList()
        val time = data.getString("time")
        val ctx = UpstreamContext(endpointUrl = endpointUrl, bearer = bearer)

        try {
            ReadOnlySqlValidator.requireReadOnly(sql)
            val result = sqlExecClient.exec(endpointUrl = endpointUrl, bearer = bearer, query = sql, asDict = asDict, withDescription = withDescription)
            val extraAttachments = mutableListOf<EmailAttachment>()

            if (attachPdf) {
                require(reportId != null) { "Para anexar PDF, informe reportId" }
                val pdfBytes = reportService.generatePdf(reportId, ctx, ReportRunRequest())
                extraAttachments += EmailAttachment(
                    filename = "report-$reportId.pdf",
                    contentType = "application/pdf",
                    bytes = pdfBytes
                )
            }

            if (compareWithSap) {
                require(!secondSql.isNullOrBlank()) { "Para comparar com SAP, informe secondSql" }
                ReadOnlySqlValidator.requireReadOnly(secondSql)
                val sourceRows = (result["data"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
                val hanaResult = hanaQueryService.exec(secondSql)
                val hasDifference = hasDifference(
                    sourceRows = sourceRows,
                    source2Rows = hanaResult.rows,
                    comparisonKey = comparisonKey,
                    tolerances = comparisonTolerances
                )
                if (sendOnlyIfDifferent && !hasDifference) {
                    log.info("Quartz job skipped scheduleId={} reason=no_difference", context.jobDetail.key.name)
                    return
                }
            }

            val sendResult = emailService.sendReport(
                EmailReportRequest(
                    sql = sql,
                    to = to,
                    cc = cc,
                    subject = subject,
                    asDict = asDict,
                    withDescription = withDescription,
                    message = message,
                    reportId = reportId,
                    attachPdf = attachPdf,
                    compareWithSap = compareWithSap,
                    secondSql = secondSql,
                    comparisonKey = comparisonKey,
                    comparisonTolerances = comparisonTolerances,
                    sendOnlyIfDifferent = sendOnlyIfDifferent,
                    time = time,
                    days = days
                ),
                result,
                extraAttachments
            )
            log.info(
                "Quartz job executed scheduleId={} sent={} previewRows={} attachedXlsx={}",
                context.jobDetail.key.name,
                sendResult.sent,
                sendResult.previewRows,
                sendResult.attachedXlsx
            )
        } catch (ex: Exception) {
            log.error("Quartz job failed scheduleId={}", context.jobDetail.key.name, ex)
            throw ex
        }
    }

    private fun hasDifference(
        sourceRows: List<Map<String, Any?>>,
        source2Rows: List<Map<String, Any?>>,
        comparisonKey: String?,
        tolerances: Map<String, Double>
    ): Boolean {
        val toleranceByField = tolerances.mapKeys { it.key.lowercase() }.mapValues { BigDecimal.valueOf(it.value) }
        return if (comparisonKey.isNullOrBlank()) {
            val remaining = source2Rows.toMutableList()
            sourceRows.forEach { row1 ->
                val idx = remaining.indexOfFirst { row2 -> rowsEqualWithTolerance(row1, row2, toleranceByField) }
                if (idx >= 0) remaining.removeAt(idx) else return true
            }
            remaining.isNotEmpty()
        } else {
            val rows1ByKey = sourceRows.groupBy { normalizeKey(it[comparisonKey]) }
            val rows2ByKey = source2Rows.groupBy { normalizeKey(it[comparisonKey]) }
            val allKeys = (rows1ByKey.keys + rows2ByKey.keys).distinct()
            allKeys.any { key ->
                val l1 = rows1ByKey[key] ?: emptyList()
                val l2 = rows2ByKey[key] ?: emptyList()
                if (l1.size != l2.size) return@any true
                l1.indices.any { idx -> !rowsEqualWithTolerance(l1[idx], l2[idx], toleranceByField) }
            }
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

    private fun parseComparisonTolerances(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0 || idx >= entry.lastIndex) return@mapNotNull null
            val key = entry.substring(0, idx).trim()
            val value = entry.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
            if (key.isBlank()) return@mapNotNull null
            key to value
        }.toMap()
    }
}
