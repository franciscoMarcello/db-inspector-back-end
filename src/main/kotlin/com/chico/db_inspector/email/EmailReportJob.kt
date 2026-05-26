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
        val attachXlsx = if (data.containsKey("attachXlsx")) data.getBooleanValue("attachXlsx") else true
        val message = data.getString("message")
        val reportId = data.getString("reportId")?.trim()?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
        val attachPdf = data.getBooleanValue("attachPdf")
        val pdfMode = normalizePdfMode(data.getString("pdfMode"))
        val pdfTitle = data.getString("pdfTitle")
        val pdfSubtitle = data.getString("pdfSubtitle")
        val pdfIncludeSummary = if (data.containsKey("pdfIncludeSummary")) data.getBooleanValue("pdfIncludeSummary") else true
        val pdfMaxRows = if (data.containsKey("pdfMaxRows")) data.getIntValue("pdfMaxRows") else 2000
        val compareWithSap = data.getBooleanValue("compareWithSap")
        val comparisonTitle = data.getString("comparisonTitle")
        val comparisonNote = data.getString("comparisonNote")
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
            var payloadForEmail = result

            if (attachPdf) {
                val pdfBytes = if (pdfMode == "scheduled_sql") {
                    emailService.generateScheduledSqlPdf(
                        title = pdfTitle,
                        subtitle = pdfSubtitle,
                        includeSummary = pdfIncludeSummary,
                        maxRows = pdfMaxRows,
                        queryResult = result
                    )
                } else {
                    require(reportId != null) { "Para anexar PDF com mode=report, informe reportId" }
                    reportService.generatePdf(reportId, ctx, ReportRunRequest())
                }
                extraAttachments += EmailAttachment(
                    filename = if (pdfMode == "scheduled_sql") "agendamento-sql.pdf" else "report-$reportId.pdf",
                    contentType = "application/pdf",
                    bytes = pdfBytes
                )
            }

            if (compareWithSap) {
                require(!secondSql.isNullOrBlank()) { "Para comparar com SAP, informe secondSql" }
                ReadOnlySqlValidator.requireReadOnly(secondSql)
                val sourceRows = (result["data"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
                val hanaResult = hanaQueryService.exec(secondSql)
                val comparison = compareRows(
                    sourceRows = sourceRows,
                    source2Rows = hanaResult.rows,
                    comparisonKey = comparisonKey,
                    tolerances = comparisonTolerances
                )
                if (sendOnlyIfDifferent && !comparison.hasDifference) {
                    log.info("Quartz job skipped scheduleId={} reason=no_difference", context.jobDetail.key.name)
                    return
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

            val sendResult = emailService.sendReport(
                EmailReportRequest(
                    sql = sql,
                    to = to,
                    cc = cc,
                    subject = subject,
                    asDict = asDict,
                    withDescription = withDescription,
                    attachXlsx = attachXlsx,
                    message = message,
                    reportId = reportId,
                    attachPdf = attachPdf,
                    pdfMode = pdfMode,
                    pdfTitle = pdfTitle,
                    pdfSubtitle = pdfSubtitle,
                    pdfIncludeSummary = pdfIncludeSummary,
                    pdfMaxRows = pdfMaxRows,
                    compareWithSap = compareWithSap,
                    comparisonTitle = comparisonTitle,
                    comparisonNote = comparisonNote,
                    secondSql = secondSql,
                    comparisonKey = comparisonKey,
                    comparisonTolerances = comparisonTolerances,
                    sendOnlyIfDifferent = sendOnlyIfDifferent,
                    time = time,
                    days = days
                ),
                payloadForEmail,
                if (attachXlsx) extraAttachments else emptyList(),
                attachTabularXlsx = attachXlsx && !compareWithSap,
                scheduleId = context.jobDetail.key.name
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

    private fun normalizePdfMode(raw: String?): String {
        val mode = raw?.trim()?.lowercase()
        return if (mode == "scheduled_sql") "scheduled_sql" else "report"
    }

    private data class ComparisonStats(
        val hasDifference: Boolean,
        val onlyInSource1: Int,
        val onlyInSource2: Int,
        val differentRows: Int,
        val matchedRows: Int
    )
}
