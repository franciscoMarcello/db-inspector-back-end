package com.chico.dbinspector.report

import org.springframework.stereotype.Service

@Service
class ReportMapperService {
    fun toResponse(entity: ReportEntity): ReportResponse {
        val created = entity.createdAt ?: error("createdAt ausente")
        val updated = entity.updatedAt ?: error("updatedAt ausente")
        return ReportResponse(
            id = entity.id?.toString() ?: error("id ausente"),
            name = entity.name,
            templateName = entity.templateName,
            sql = entity.sql,
            description = entity.description,
            archived = entity.archived,
            folder = entity.folder?.let { reportFolder ->
                ReportFolderSummaryResponse(
                    id = reportFolder.id?.toString() ?: error("id da pasta ausente"),
                    name = reportFolder.name,
                    archived = reportFolder.archived
                )
            },
            jasperTemplate = entity.jasperTemplate?.let { template ->
                JasperTemplateSummaryResponse(
                    id = template.id?.toString() ?: error("id do template Jasper ausente"),
                    name = template.name,
                    archived = template.archived
                )
            },
            variables = entity.variables
                .sortedBy { it.orderIndex }
                .map { variable ->
                    ReportVariableResponse(
                        id = variable.id?.toString() ?: error("id da variavel ausente"),
                        key = variable.key,
                        label = variable.label,
                        type = variable.type,
                        required = variable.required,
                        multiple = variable.multiple,
                        defaultValue = variable.defaultValue,
                        optionsSql = variable.optionsSql,
                        orderIndex = variable.orderIndex
                    )
                },
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli(),
            secondSql = entity.secondSql,
            comparisonKey = entity.comparisonKey
        )
    }
}
