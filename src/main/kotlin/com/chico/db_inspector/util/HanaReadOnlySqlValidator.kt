package com.chico.dbinspector.util

/**
 * Regra extra para SQL que a IA manda ao SAP HANA, por cima do [ReadOnlySqlValidator] (comeca com SELECT/WITH,
 * uma consulta so, sem DML/DDL classico). Bloqueia o que ainda cabe dentro de um SELECT e tem efeito colateral
 * no HANA, mais palavras de comando como segunda barreira. Falso positivo (palavra dentro de texto) e aceito:
 * a IA reescreve; deixar passar escrita nao.
 */
object HanaReadOnlySqlValidator {
    private val forbiddenWords = listOf(
        // efeito colateral dentro de SELECT
        "nextval", "lock", "into",
        // comandos que nunca podem aparecer
        "insert", "update", "delete", "upsert", "merge", "truncate",
        "create", "alter", "drop", "rename", "comment",
        "grant", "revoke", "call", "exec", "execute", "do",
        "commit", "rollback", "savepoint", "set", "connect", "disconnect",
        "import", "export", "load", "unload", "backup", "recover"
    )
    private val forbiddenRegex = Regex("\\b(${forbiddenWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    fun validate(sql: String): String? {
        ReadOnlySqlValidator.validate(sql)?.let { return it }
        // Comentario poderia esconder trecho do leitor humano da auditoria; nao ha motivo para a IA usar.
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/")) return "SQL do SAP nao pode ter comentario"
        forbiddenRegex.find(sql)?.let { return "SQL do SAP deve ser somente leitura: '${it.value}' nao e permitido" }
        return null
    }

    fun requireReadOnly(sql: String) {
        val error = validate(sql)
        require(error == null) { error ?: "SQL invalida" }
    }
}
