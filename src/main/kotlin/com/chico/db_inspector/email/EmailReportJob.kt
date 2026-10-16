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
import java.util.UUID

@Component
class EmailReportJob(
    private val sqlExecClient: SqlExecClient,
    private val emailService: EmailReportService,
    private val comparisonService: EmailComparisonService,
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
        val comparisonTolerances = comparisonService.parseTolerances(data.getString("comparisonTolerances"))
        val comparisonTolerancePercents = comparisonService.parseTolerances(data.getString("comparisonTolerancePercents"))
        val comparisonColumns = comparisonService.parseColumns(data.getString("comparisonColumns"))
        val comparisonIgnoreColumns = comparisonService.parseColumns(data.getString("comparisonIgnoreColumns"))
        val comparisonGroupBy = data.getString("comparisonGroupBy")?.trim().takeUnless { it.isNullOrBlank() }
        val comparisonValueColumn = data.getString("comparisonValueColumn")?.trim().takeUnless { it.isNullOrBlank() }
        val comparisonTopN = if (data.containsKey("comparisonTopN")) data.getIntValue("comparisonTopN") else 10
        val sendOnlyIfDifferent = if (data.containsKey("sendOnlyIfDifferent")) data.getBooleanValue("sendOnlyIfDifferent") else true
        val days = data.getString("days")?.split(",") ?: emptyList()
        val time = data.getString("time")
        val ctx = UpstreamContext(endpointUrl = endpointUrl, bearer = bearer)

        try {
            ReadOnlySqlValidator.requireReadOnly(sql)
            val result = sqlExecClient.exec(endpointUrl = endpointUrl, bearer = bearer, query = sql, asDict = asDict, withDescription = withDescription)
            val extraAttachments = mutableListOf<EmailAttachment>()
            var payloadForEmail = result
            var comparisonDetail: ComparisonEmailDetail? = null

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
                val comparison = comparisonService.compareRows(
                    sourceRows = sourceRows,
                    source2Rows = hanaResult.rows,
                    options = ComparisonOptions(
                        comparisonKey = comparisonKey,
                        tolerances = comparisonTolerances,
                        tolerancePercents = comparisonTolerancePercents,
                        columns = comparisonColumns,
                        ignoreColumns = comparisonIgnoreColumns
                    )
                )
                if (sendOnlyIfDifferent && !comparison.hasDifference) {
                    log.info("Quartz job skipped scheduleId={} reason=no_difference", context.jobDetail.key.name)
                    return
                }

                comparisonDetail = comparisonService.buildDetail(
                    differences = comparison.differences,
                    source1Label = "agromobi",
                    source2Label = "sap",
                    groupByColumn = comparisonGroupBy,
                    valueColumn = comparisonValueColumn,
                    topN = comparisonTopN
                )

                val comparisonWorkbook = EmailReportFormatter.buildComparisonWorkbook(
                    source1Label = "agromobi",
                    source1Rows = sourceRows,
                    source2Label = "sap",
                    source2Rows = hanaResult.rows,
                    differences = comparison.differences,
                    groups = comparisonDetail?.groups ?: emptyList(),
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
                    comparisonTolerancePercents = comparisonTolerancePercents,
                    comparisonColumns = comparisonColumns,
                    comparisonIgnoreColumns = comparisonIgnoreColumns,
                    comparisonGroupBy = comparisonGroupBy,
                    comparisonValueColumn = comparisonValueColumn,
                    comparisonTopN = comparisonTopN,
                    sendOnlyIfDifferent = sendOnlyIfDifferent,
                    time = time,
                    days = days
                ),
                payloadForEmail,
                if (attachXlsx) extraAttachments else emptyList(),
                attachTabularXlsx = attachXlsx && !compareWithSap,
                scheduleId = context.jobDetail.key.name,
                comparisonDetail = comparisonDetail
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

    private fun normalizePdfMode(raw: String?): String {
        val mode = raw?.trim()?.lowercase()
        return if (mode == "scheduled_sql") "scheduled_sql" else "report"
    }
}
