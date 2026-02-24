package com.chico.dbinspector.email

import com.chico.dbinspector.service.SqlExecClient
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailReportJob(
    private val sqlExecClient: SqlExecClient,
    private val emailService: EmailReportService
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
        val days = data.getString("days")?.split(",") ?: emptyList()
        val time = data.getString("time")

        try {
            val result = sqlExecClient.exec(
                endpointUrl = endpointUrl,
                bearer = bearer,
                query = sql,
                asDict = asDict,
                withDescription = withDescription
            )
            val sendResult = emailService.sendReport(
                EmailReportRequest(
                    sql = sql,
                    to = to,
                    cc = cc,
                    subject = subject,
                    asDict = asDict,
                    withDescription = withDescription,
                    time = time,
                    days = days
                ),
                result
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
}
