package com.chico.dbinspector.report

import jakarta.validation.constraints.NotBlank

data class ReportRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val templateName: String,
    @field:NotBlank
    val sql: String,
    val description: String? = null
)

data class ReportResponse(
    val id: String,
    val name: String,
    val templateName: String,
    val sql: String,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class ReportRunMeta(
    val environment: String,
    val generatedAt: String,
    val lastRunAt: String,
    val rowCount: Int,
    val elapsedMs: Long,
    val truncated: Boolean
)

data class ReportSummary(
    val column: String,
    val sum: Double
)

data class ReportRunResponse(
    val name: String,
    val meta: ReportRunMeta,
    val query: String,
    val columns: List<String>,
    val rows: List<Map<String, Any?>>,
    val summaries: List<ReportSummary>
)
