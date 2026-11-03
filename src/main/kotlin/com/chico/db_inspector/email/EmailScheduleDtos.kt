package com.chico.dbinspector.email

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
    val attachXlsx: Boolean,
    val message: String?,
    val reportId: UUID?,
    val attachPdf: Boolean,
    val pdfMode: String,
    val pdfTitle: String?,
    val pdfSubtitle: String?,
    val pdfIncludeSummary: Boolean,
    val pdfMaxRows: Int,
    val compareWithSap: Boolean,
    val comparisonTitle: String?,
    val comparisonNote: String?,
    val secondSql: String?,
    val comparisonKey: String?,
    val comparisonTolerances: Map<String, Double>,
    val comparisonTolerancePercents: Map<String, Double>,
    val comparisonColumns: List<String>,
    val comparisonIgnoreColumns: List<String>,
    val comparisonGroupBy: String?,
    val comparisonValueColumn: String?,
    val comparisonTopN: Int,
    val sendOnlyIfDifferent: Boolean
)

data class ComparisonPreviewRequest(
    @field:NotBlank
    @field:Size(max = 65535)
    val sql: String,

    @field:NotBlank
    @field:Size(max = 65535)
    val secondSql: String,

    @field:Size(max = 128)
    val comparisonKey: String? = null,
    val comparisonTolerances: Map<String, Double> = emptyMap(),
    val comparisonTolerancePercents: Map<String, Double> = emptyMap(),
    val comparisonColumns: List<String> = emptyList(),
    val comparisonIgnoreColumns: List<String> = emptyList(),
    @field:Size(max = 128)
    val comparisonGroupBy: String? = null,
    @field:Size(max = 128)
    val comparisonValueColumn: String? = null,
    val sampleLimit: Int? = 50
)

data class ComparisonDifferenceView(
    val type: String,
    val key: String?,
    val column: String?,
    val value1: String?,
    val value2: String?,
    val delta: Double?,
    val row: Map<String, Any?>
)

data class ComparisonPreviewResponse(
    val status: String,
    val onlyInSource1: Int,
    val onlyInSource2: Int,
    val differentRows: Int,
    val matchedRows: Int,
    val divergencePercent: Double,
    val columnsSource1: List<String>,
    val columnsSource2: List<String>,
    val comparedColumns: List<String>,
    val groups: List<ComparisonGroupSummary>,
    val differencesSample: List<ComparisonDifferenceView>,
    val differencesTotal: Int
)
