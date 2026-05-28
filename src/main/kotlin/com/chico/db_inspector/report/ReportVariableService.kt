package com.chico.dbinspector.report

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.regex.Pattern

data class OptionColumns(val valor: String, val descricao: String)

@Service
class ReportVariableService {
    private val allowedVariableTypes = setOf("string", "number", "date", "datetime", "boolean")
    private val optionColumnValor = "valor"
    private val optionColumnDescricao = "descricao"
    private val writeKeywordsRegex =
        Regex("\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke)\\b", RegexOption.IGNORE_CASE)

    fun normalizeVariables(input: List<ReportVariableRequest>): List<ReportVariableEntity> {
        val seenKeys = mutableSetOf<String>()
        return input.mapIndexed { index, variable ->
            val key = variable.key.trim()
            val label = variable.label.trim()
            val type = variable.type.trim().lowercase()
            require(type in allowedVariableTypes) {
                "Tipo de variavel invalido: '$type'. Use string, number, date, datetime ou boolean"
            }
            require(seenKeys.add(key.lowercase())) { "Variavel duplicada: '$key'" }

            ReportVariableEntity(
                key = key,
                label = label,
                type = type,
                required = variable.required,
                multiple = variable.multiple,
                defaultValue = variable.defaultValue?.trim().takeUnless { it.isNullOrBlank() },
                optionsSql = variable.optionsSql?.trim().takeUnless { it.isNullOrBlank() },
                orderIndex = variable.orderIndex ?: index
            )
        }
    }

    fun normalizeOptionsSql(optionsSql: String?): String {
        val query = optionsSql?.trim().orEmpty()
        if (query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Variavel sem SQL de opcoes configurado")
        }

        val withoutTrailingSemicolon = query.removeSuffix(";").trim()
        require(withoutTrailingSemicolon.isNotBlank()) { "SQL de opcoes nao pode ser vazio" }
        require(!withoutTrailingSemicolon.contains(";")) { "SQL de opcoes deve conter apenas uma consulta" }
        val startsLikeSelect = withoutTrailingSemicolon.startsWith("select", ignoreCase = true) ||
            withoutTrailingSemicolon.startsWith("with", ignoreCase = true)
        require(startsLikeSelect) { "SQL de opcoes deve comecar com SELECT ou WITH" }
        require(!writeKeywordsRegex.containsMatchIn(withoutTrailingSemicolon)) { "SQL de opcoes deve ser somente leitura" }

        return withoutTrailingSemicolon
    }

    fun extractOptionRows(result: Map<String, Any?>): List<Map<String, Any?>> {
        val data = result["data"] as? List<*> ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        return data.mapIndexed { index, raw ->
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException(
                "Linha ${index + 1} do SQL de opcoes nao esta no formato objeto"
            )
            map.entries
                .filter { it.key is String }
                .associate { (key, value) -> key as String to value }
        }
    }

    fun extractOptionColumnsFromDescription(result: Map<String, Any?>): Set<String>? {
        val description = result["description"] as? List<*> ?: return null
        val keys = description.mapNotNull { raw ->
            val column = raw as? Map<*, *> ?: return@mapNotNull null
            val name = column["name"] ?: column["column_name"] ?: column["columnName"] ?: column["field"]
            name?.toString()
        }.toSet()
        return keys.takeIf { it.isNotEmpty() }
    }

    fun validateOptionColumns(columns: Collection<String>): OptionColumns {
        require(columns.size == 2) {
            "SQL de opcoes deve retornar exatamente 2 colunas: valor e descricao"
        }

        val valorKey = columns.firstOrNull { it.equals(optionColumnValor, ignoreCase = true) }
        val descricaoKey = columns.firstOrNull { it.equals(optionColumnDescricao, ignoreCase = true) }
        require(valorKey != null && descricaoKey != null) {
            "SQL de opcoes deve retornar as colunas 'valor' e 'descricao'"
        }

        return OptionColumns(valor = valorKey, descricao = descricaoKey)
    }
}
