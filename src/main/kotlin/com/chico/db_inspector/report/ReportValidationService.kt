package com.chico.dbinspector.report

import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.stereotype.Service

@Service
class ReportValidationService(
    private val variableService: ReportVariableService,
    private val queryService: ReportQueryService,
    private val sqlExecClient: SqlExecClient
) {
    private val placeholderRegex = Regex("(?<!:):([A-Za-z_][A-Za-z0-9_]*)")

    fun validate(request: ReportValidationRequest, ctx: UpstreamContext): ReportValidationResponse {
        val errors = mutableListOf<String>()
        val queryTemplate = request.sql.trim()
        val variables = try {
            variableService.normalizeVariables(request.variables)
        } catch (ex: IllegalArgumentException) {
            errors += ex.message ?: "Configuracao de variaveis invalida"
            emptyList()
        }

        if (queryTemplate.isBlank()) {
            errors += "SQL nao pode ser vazia"
            return ReportValidationResponse(valid = false, errors = errors, renderedQuery = null)
        }

        if (request.enforceReadOnly) {
            ReadOnlySqlValidator.validate(queryTemplate)?.let { errors += it }
        }

        val placeholdersInQuery = placeholderRegex.findAll(queryTemplate).map { it.groupValues[1] }.toSet()
        val variableKeys = variables.map { it.key }.toSet()
        val unknownPlaceholders = placeholdersInQuery - variableKeys
        if (unknownPlaceholders.isNotEmpty()) {
            errors += "Placeholders sem variavel configurada: ${unknownPlaceholders.joinToString(", ")}"
        }

        variables.forEach { variable ->
            if (!placeholdersInQuery.contains(variable.key)) return@forEach
            if (!variable.multiple) return@forEach

            val hasWrongInSyntax = Regex("\\bin\\s*\\(\\s*:${Regex.escape(variable.key)}\\s*\\)", RegexOption.IGNORE_CASE)
                .containsMatchIn(queryTemplate)
            if (hasWrongInSyntax) {
                errors += "Variavel multipla '${variable.key}' deve usar 'IN :${variable.key}' (sem parenteses)"
                return@forEach
            }

            val hasExpectedInSyntax = Regex("\\bin\\s*:${Regex.escape(variable.key)}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(queryTemplate)
            if (!hasExpectedInSyntax) {
                errors += "Variavel multipla '${variable.key}' deve ser usada com IN :${variable.key}"
            }
        }

        val renderedQuery = runCatching {
            queryService.buildQueryWithParams(queryTemplate, variables, request.params, request.enforceRequired)
        }.getOrElse { ex ->
            errors += ex.message ?: "Falha ao montar SQL"
            null
        }

        if (errors.isNotEmpty() || renderedQuery == null) {
            return ReportValidationResponse(valid = false, errors = errors, renderedQuery = renderedQuery)
        }

        if (request.validateSyntax) {
            runCatching {
                sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, "EXPLAIN $renderedQuery", asDict = true, withDescription = true)
            }.onFailure { ex ->
                errors += "Falha de sintaxe/execucao no banco: ${ex.message ?: "erro desconhecido"}"
            }
        }

        return ReportValidationResponse(valid = errors.isEmpty(), errors = errors, renderedQuery = renderedQuery)
    }
}
