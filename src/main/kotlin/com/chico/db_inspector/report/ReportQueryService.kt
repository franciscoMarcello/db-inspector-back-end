package com.chico.dbinspector.report

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Service
class ReportQueryService {
    private val dateTimeSqlFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun buildQueryWithParams(
        queryTemplate: String,
        variables: List<ReportVariableEntity>,
        params: Map<String, Any?>,
        enforceRequired: Boolean = true
    ): String {
        if (variables.isEmpty()) return queryTemplate

        val knownKeys = variables.map { it.key }.toSet()
        val unknown = params.keys.filterNot { knownKeys.contains(it) }
        require(unknown.isEmpty()) { "Parametros desconhecidos: ${unknown.joinToString(", ")}" }

        var rendered = queryTemplate
        variables.forEach { variable ->
            val rawValue = resolveVariableValue(variable, params, enforceRequired)
            val sqlLiteral = toSqlLiteral(variable, rawValue)
            val placeholder = Regex("(?<!:):${Regex.escape(variable.key)}\\b")
            rendered = rendered.replace(placeholder, sqlLiteral)
        }
        return rendered
    }

    private fun resolveVariableValue(
        variable: ReportVariableEntity,
        params: Map<String, Any?>,
        enforceRequired: Boolean
    ): Any? {
        if (params.containsKey(variable.key)) {
            return params[variable.key]
        }
        if (!variable.defaultValue.isNullOrBlank()) {
            return variable.defaultValue
        }
        if (variable.required && enforceRequired) {
            throw IllegalArgumentException("Parametro obrigatorio ausente: '${variable.key}'")
        }
        return null
    }

    private fun toSqlLiteral(variable: ReportVariableEntity, rawValue: Any?): String {
        if (!variable.multiple) {
            if (rawValue == null) return "NULL"
            return toSingleSqlLiteral(variable, rawValue)
        }

        if (rawValue == null) return "(NULL)"

        val items = when (rawValue) {
            is Collection<*> -> rawValue.toList()
            is Array<*> -> rawValue.toList()
            else -> listOf(rawValue)
        }
        require(items.isNotEmpty()) { "Parametro '${variable.key}' nao pode ser lista vazia" }

        val sqlItems = items.map { item ->
            require(item != null) { "Parametro '${variable.key}' nao pode conter valores nulos" }
            toSingleSqlLiteral(variable, item)
        }
        return sqlItems.joinToString(prefix = "(", postfix = ")")
    }

    private fun toSingleSqlLiteral(variable: ReportVariableEntity, rawValue: Any): String {
        return when (variable.type) {
            "string" -> "'${escapeSqlString(rawValue.toString())}'"
            "number" -> parseNumber(variable.key, rawValue).toPlainString()
            "date" -> "'${parseDate(variable.key, rawValue)}'"
            "datetime" -> "'${parseDateTime(variable.key, rawValue)}'"
            "boolean" -> parseBoolean(variable.key, rawValue).toString()
            else -> throw IllegalArgumentException("Tipo de variavel invalido: '${variable.type}'")
        }
    }

    private fun parseNumber(key: String, rawValue: Any): BigDecimal =
        when (rawValue) {
            is Number -> BigDecimal(rawValue.toString())
            is String -> rawValue.trim().let {
                runCatching { BigDecimal(it) }.getOrElse {
                    throw IllegalArgumentException("Parametro '$key' deve ser number")
                }
            }
            else -> throw IllegalArgumentException("Parametro '$key' deve ser number")
        }

    private fun parseDate(key: String, rawValue: Any): String {
        val text = rawValue.toString().trim()
        return try {
            LocalDate.parse(text).toString()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Parametro '$key' deve ser date (yyyy-MM-dd)")
        }
    }

    private fun parseDateTime(key: String, rawValue: Any): String {
        val text = rawValue.toString().trim()
        val value = runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: throw IllegalArgumentException("Parametro '$key' deve ser datetime (ISO-8601)")

        return value.format(dateTimeSqlFormatter)
    }

    private fun parseBoolean(key: String, rawValue: Any): Boolean =
        when (rawValue) {
            is Boolean -> rawValue
            is String -> when (rawValue.trim().lowercase()) {
                "true", "1", "yes", "y", "sim", "s" -> true
                "false", "0", "no", "n", "nao", "não" -> false
                else -> throw IllegalArgumentException("Parametro '$key' deve ser boolean")
            }
            else -> throw IllegalArgumentException("Parametro '$key' deve ser boolean")
        }

    private fun escapeSqlString(value: String): String = value.replace("'", "''")
}
