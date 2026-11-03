package com.chico.dbinspector.util

/**
 * Regra extra para SQL que a IA manda ao SAP HANA, por cima do [ReadOnlySqlValidator] (comeca com SELECT/WITH,
 * uma consulta so, sem DML/DDL classico). Bloqueia o que ainda cabe dentro de um SELECT e tem efeito colateral
 * no HANA, mais palavras de comando como segunda barreira. Falso positivo (palavra dentro de texto) e aceito:
 * a IA reescreve; deixar passar escrita nao. Tambem esconde senha, chave e token: proibe citar a coluna e
 * proibe `*` que expande colunas, para toda coluna devolvida ter sido escrita no SQL.
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

    // Senha, chave e token gravados por B1 e add-ons (OUSR.PASSWORD, SenhaCertificado, ApiCloudFioriSecret...).
    private val sensitiveName = Regex(
        "[A-Za-z0-9_@]*(passw|pwd|senha|secret|token|apikey|api_key|credenc|credential|privatekey|chaveprivada|chave_privada)[A-Za-z0-9_]*",
        RegexOption.IGNORE_CASE
    )

    // `*` que expande colunas (T0.*, SELECT * FROM, SELECT *, x). COUNT(*) e multiplicacao passam. Sem isso,
    // UNION ALL SELECT * ou FOR JSON trariam a coluna sensivel com outro nome e escapariam da regra acima.
    private val starExpansion = Regex("\\.\\s*\\*|\\*\\s*(,|from\\b)", RegexOption.IGNORE_CASE)

    fun isSensitiveColumn(name: String): Boolean = sensitiveName.matches(name)

    fun validate(sql: String): String? {
        ReadOnlySqlValidator.validate(sql)?.let { return it }
        // Comentario poderia esconder trecho do leitor humano da auditoria; nao ha motivo para a IA usar.
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/")) return "SQL do SAP nao pode ter comentario"
        forbiddenRegex.find(sql)?.let { return "SQL do SAP deve ser somente leitura: '${it.value}' nao e permitido" }
        if (starExpansion.containsMatchIn(sql)) {
            return "SQL do SAP nao pode usar * para trazer colunas: liste as colunas (nomes em SYS.TABLE_COLUMNS)"
        }
        sensitiveName.find(sql)?.let { return "SQL do SAP nao pode ler senha, chave ou token: '${it.value}' nao e permitido" }
        return null
    }

    fun requireReadOnly(sql: String) {
        val error = validate(sql)
        require(error == null) { error ?: "SQL invalida" }
    }
}
