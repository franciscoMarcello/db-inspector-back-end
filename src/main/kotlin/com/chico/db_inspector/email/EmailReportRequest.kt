package com.chico.dbinspector.email

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class EmailReportRequest(
    @field:NotBlank
    @field:Size(max = 65535)
    val sql: String,

    @field:NotBlank
    @field:Size(max = 2048)
    val to: String,

    @field:Size(max = 2048)
    val cc: String? = null,

    @field:Size(max = 256)
    val subject: String? = null,

    val asDict: Boolean? = true,
    val withDescription: Boolean? = true,
    val attachXlsx: Boolean? = true,
    @field:Size(max = 4000)
    val message: String? = null,
    val reportId: UUID? = null,
    val attachPdf: Boolean? = false,
    @field:Size(max = 32)
    val pdfMode: String? = "report",
    @field:Size(max = 120)
    val pdfTitle: String? = null,
    @field:Size(max = 180)
    val pdfSubtitle: String? = null,
    val pdfIncludeSummary: Boolean? = true,
    val pdfMaxRows: Int? = 2000,
    val compareWithSap: Boolean? = false,
    @field:Size(max = 120)
    val comparisonTitle: String? = null,
    @field:Size(max = 280)
    val comparisonNote: String? = null,
    @field:Size(max = 65535)
    val secondSql: String? = null,
    @field:Size(max = 128)
    val comparisonKey: String? = null,
    val comparisonTolerances: Map<String, Double> = emptyMap(),
    val sendOnlyIfDifferent: Boolean? = true,

    /** Time in HH:mm (e.g. "08:30") for recurring schedule. */
    @field:Size(min = 4, max = 5)
    val time: String? = null,

    /** Weekday abbreviations for schedule (mon,tue,wed,thu,fri,sat,sun). */
    @field:Size(min = 1, max = 7)
    val days: List<String>? = null
)
