package com.chico.dbinspector.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("dbinspector")
data class DbInspectorProperties(
    var sqlExecBaseUrl: String = "",
    var apitoken: String = "",
    var mail: MailProperties = MailProperties(),
    var schedule: ScheduleProperties = ScheduleProperties()
) {
    data class MailProperties(
        var from: String = "no-reply@dbinspector.local"
    )

    data class ScheduleProperties(
        var previewLimit: Int = 200,
        var attachmentRowLimit: Int = 5_000,
        var attachmentSizeLimitBytes: Long = 5 * 1024 * 1024,
        var sqlTimeoutMs: Long = 15_000
    )
}
