package com.chico.dbinspector.email

import java.util.UUID

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
    val withDescription: Boolean,
    val message: String?,
    val reportId: UUID?,
    val attachPdf: Boolean,
    val compareWithSap: Boolean,
    val secondSql: String?,
    val comparisonKey: String?,
    val comparisonTolerances: Map<String, Double>,
    val sendOnlyIfDifferent: Boolean
)
