package com.chico.dbinspector.util

object ReadOnlySqlValidator {
    private val writeKeywordsRegex =
        Regex("\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke)\\b", RegexOption.IGNORE_CASE)

    fun validate(queryTemplate: String): String? {
        val query = queryTemplate.trim().removeSuffix(";").trim()
        if (query.isBlank()) return "SQL nao pode ser vazia"
        if (query.contains(";")) return "SQL deve conter apenas uma consulta"

        val startsLikeSelect = query.startsWith("select", ignoreCase = true) ||
            query.startsWith("with", ignoreCase = true)
        if (!startsLikeSelect) return "SQL deve comecar com SELECT ou WITH"

        if (writeKeywordsRegex.containsMatchIn(query)) return "SQL deve ser somente leitura"
        return null
    }

    fun requireReadOnly(queryTemplate: String) {
        val error = validate(queryTemplate)
        require(error == null) { error ?: "SQL invalida" }
    }
}
