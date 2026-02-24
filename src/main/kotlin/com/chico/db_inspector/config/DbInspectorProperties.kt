package com.chico.dbinspector.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("dbinspector")
data class DbInspectorProperties(
    var sqlExecBaseUrl: String = "",
    var apitoken: String = "",
    var environment: String = "Prod",
    var mail: MailProperties = MailProperties(),
    var schedule: ScheduleProperties = ScheduleProperties(),
    var reports: ReportsProperties = ReportsProperties(),
    var security: SecurityProperties = SecurityProperties()
) {
    data class MailProperties(
        var from: String = "no-reply@dbinspector.local"
    )

    data class ScheduleProperties(
        var previewLimit: Int = 200,
        var attachmentRowLimit: Int = 5_000,
        var attachmentSizeLimitBytes: Long = 5 * 1024 * 1024,
        var sqlTimeoutMs: Long = 15_000,
        var timeZone: String = "America/Porto_Velho"
    )

    data class ReportsProperties(
        var maxRows: Int = 500
    )

    data class SecurityProperties(
        var aclDefaultDeny: Boolean = false,
        var loginMaxAttempts: Int = 5,
        var loginWindowSeconds: Int = 60
    )
}
