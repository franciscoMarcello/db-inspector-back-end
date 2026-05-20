package com.chico.dbinspector.dashboard

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class DashboardFilterDefinition(
    @field:NotBlank
    val key: String,
    @field:NotBlank
    val label: String,
    @field:NotBlank
    val type: String,
    val defaultValue: String? = null
)

data class DashboardRequest(
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotNull
    val system: DashboardSystem,
    @JsonAlias("filters_json")
    val filtersJson: List<DashboardFilterDefinition>? = null,
    val archived: Boolean? = null
)

data class DashboardResponse(
    val id: String,
    val name: String,
    val description: String?,
    val system: DashboardSystem,
    @JsonAlias("filters_json")
    val filtersJson: List<DashboardFilterDefinition>,
    @get:JsonProperty("filters_json")
    val filtersJsonSnake: List<DashboardFilterDefinition> = filtersJson,
    val archived: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val widgets: List<DashboardWidgetResponse>
)

data class DashboardWidgetRequest(
    @field:NotBlank
    val title: String,
    @field:NotNull
    val type: WidgetType,
    @field:NotBlank
    val querySql: String,
    val configJson: JsonNode? = null,
    val layoutJson: JsonNode? = null,
    val positionOrder: Int? = null
)

data class DashboardWidgetResponse(
    val id: String,
    val dashboardId: String,
    val title: String,
    val type: WidgetType,
    val querySql: String,
    val configJson: JsonNode,
    val layoutJson: JsonNode,
    val positionOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class DashboardWidgetRunRequest(
    @JsonAlias("filters")
    val params: Map<String, Any?> = emptyMap(),
    val limit: Int? = null
) {
    private val extraParams: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun putExtra(key: String, value: Any?) {
        if (key != "params" && key != "filters" && key != "limit") {
            extraParams[key] = value
        }
    }

    fun resolvedParams(): Map<String, Any?> =
        if (params.isNotEmpty()) params else extraParams.toMap()
}

data class WidgetRunMeta(
    val elapsedMs: Long,
    val rowCount: Int,
    val truncated: Boolean
)

data class ChartHint(
    val dimension: String? = null,
    val metric: String? = null,
    val suggestedType: String? = null
)

data class DashboardWidgetRunResponse(
    val meta: WidgetRunMeta,
    val columns: List<String>,
    val rows: List<Map<String, Any?>>,
    val chartHint: ChartHint? = null
)

internal fun emptyJsonNode(): JsonNode = JsonNodeFactory.instance.objectNode()

data class DashboardFilter(
    val system: DashboardSystem? = null,
    val archived: Boolean? = null
)
