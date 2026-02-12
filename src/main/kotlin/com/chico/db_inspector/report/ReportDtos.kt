package com.chico.dbinspector.report

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.util.UUID

data class ReportRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    val templateName: String,
    @field:NotBlank
    val sql: String,
    val description: String? = null,
    val archived: Boolean? = null,
    val folderId: UUID? = null,
    val jasperTemplateId: UUID? = null,
    @field:Valid
    val variables: List<ReportVariableRequest> = emptyList()
)

data class ReportVariableRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$")
    val key: String,
    @field:NotBlank
    val label: String,
    @field:NotBlank
    val type: String,
    val required: Boolean = true,
    val defaultValue: String? = null,
    val optionsSql: String? = null,
    val orderIndex: Int? = null
)

data class ReportResponse(
    val id: String,
    val name: String,
    val templateName: String,
    val sql: String,
    val description: String?,
    val archived: Boolean,
    val folder: ReportFolderSummaryResponse?,
    val jasperTemplate: JasperTemplateSummaryResponse?,
    val variables: List<ReportVariableResponse>,
    val createdAt: Long,
    val updatedAt: Long
)

data class ReportFolderSummaryResponse(
    val id: String,
    val name: String,
    val archived: Boolean
)

data class JasperTemplateSummaryResponse(
    val id: String,
    val name: String,
    val archived: Boolean
)

data class ReportVariableResponse(
    val id: String,
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String?,
    val optionsSql: String?,
    val orderIndex: Int
)

data class ReportVariableOptionsRequest(
    val params: Map<String, Any?> = emptyMap(),
    val limit: Int? = null
)

data class ReportVariableOptionResponse(
    val valor: Any?,
    val descricao: String
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

data class ReportRunRequest(
    val params: Map<String, Any?> = emptyMap(),
    val safe: Boolean = false
)

data class ReportRunResponse(
    val name: String,
    val meta: ReportRunMeta,
    val query: String,
    val columns: List<String>,
    val rows: List<Map<String, Any?>>,
    val summaries: List<ReportSummary>
)

data class ReportFolderRequest(
    @field:NotBlank
    val name: String,
    val description: String? = null,
    val archived: Boolean? = null
)

data class ReportFolderResponse(
    val id: String,
    val name: String,
    val description: String?,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class JasperTemplateRequest(
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotBlank
    val jrxml: String,
    val archived: Boolean? = null
)

data class JasperTemplateResponse(
    val id: String,
    val name: String,
    val description: String?,
    val jrxml: String,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
