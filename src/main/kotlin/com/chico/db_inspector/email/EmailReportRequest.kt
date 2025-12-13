package com.chico.dbinspector.email

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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

    /** Time in HH:mm (e.g. "08:30") for recurring schedule. */
    @field:Size(min = 4, max = 5)
    val time: String? = null,

    /** Weekday abbreviations for schedule (mon,tue,wed,thu,fri,sat,sun). */
    @field:Size(min = 1, max = 7)
    val days: List<String>? = null
)
