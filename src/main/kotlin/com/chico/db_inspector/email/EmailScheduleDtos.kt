package com.chico.dbinspector.email

data class EmailScheduleResponse(
    val id: String,
    val cron: String,
    val nextRun: String?,
    val status: String,
    val time: String,
    val days: List<String>,
    val sql: String,
    val to: String,
    val cc: String?,
    val subject: String?,
    val asDict: Boolean,
    val withDescription: Boolean
)
