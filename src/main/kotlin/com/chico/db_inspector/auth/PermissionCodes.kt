package com.chico.dbinspector.auth

object PermissionCodes {
    const val REPORT_READ = "REPORT_READ"
    const val REPORT_WRITE = "REPORT_WRITE"
    const val REPORT_RUN = "REPORT_RUN"

    const val FOLDER_READ = "FOLDER_READ"
    const val FOLDER_WRITE = "FOLDER_WRITE"

    const val TEMPLATE_READ = "TEMPLATE_READ"
    const val TEMPLATE_WRITE = "TEMPLATE_WRITE"

    const val SQL_METADATA_READ = "SQL_METADATA_READ"
    const val SQL_QUERY_EXECUTE = "SQL_QUERY_EXECUTE"

    const val EMAIL_SEND = "EMAIL_SEND"
    const val EMAIL_TEST = "EMAIL_TEST"
    const val EMAIL_SCHEDULE_READ = "EMAIL_SCHEDULE_READ"
    const val EMAIL_SCHEDULE_WRITE = "EMAIL_SCHEDULE_WRITE"

    val all = listOf(
        REPORT_READ,
        REPORT_WRITE,
        REPORT_RUN,
        FOLDER_READ,
        FOLDER_WRITE,
        TEMPLATE_READ,
        TEMPLATE_WRITE,
        SQL_METADATA_READ,
        SQL_QUERY_EXECUTE,
        EMAIL_SEND,
        EMAIL_TEST,
        EMAIL_SCHEDULE_READ,
        EMAIL_SCHEDULE_WRITE
    )
}
