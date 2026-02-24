package com.chico.dbinspector.auth

object PermissionCodes {
    const val REPORT_READ = "REPORT_READ"
    const val REPORT_WRITE = "REPORT_WRITE"
    const val REPORT_RUN = "REPORT_RUN"

    const val FOLDER_READ = "FOLDER_READ"
    const val FOLDER_WRITE = "FOLDER_WRITE"

    const val TEMPLATE_READ = "TEMPLATE_READ"
    const val TEMPLATE_WRITE = "TEMPLATE_WRITE"

    const val SQL_METADATA_READ = "SQL_METADATA_READ"
    const val SQL_QUERY_EXECUTE = "SQL_QUERY_EXECUTE"

    const val EMAIL_SEND = "EMAIL_SEND"
    const val EMAIL_TEST = "EMAIL_TEST"
    const val EMAIL_SCHEDULE_READ = "EMAIL_SCHEDULE_READ"
    const val EMAIL_SCHEDULE_WRITE = "EMAIL_SCHEDULE_WRITE"

    val catalog = listOf(
        PermissionView(REPORT_READ, "Visualizar Relatorios", "Permite listar e visualizar relatorios"),
        PermissionView(REPORT_WRITE, "Gerenciar Relatorios", "Permite criar, editar e excluir relatorios"),
        PermissionView(REPORT_RUN, "Executar Relatorios", "Permite executar relatorios e gerar PDF"),
        PermissionView(FOLDER_READ, "Visualizar Pastas", "Permite listar e visualizar pastas"),
        PermissionView(FOLDER_WRITE, "Gerenciar Pastas", "Permite criar, editar e excluir pastas"),
        PermissionView(TEMPLATE_READ, "Visualizar Templates", "Permite listar e visualizar templates Jasper"),
        PermissionView(TEMPLATE_WRITE, "Gerenciar Templates", "Permite criar, editar e excluir templates Jasper"),
        PermissionView(SQL_METADATA_READ, "Visualizar Metadados SQL", "Permite consultar schemas, tabelas e relacoes"),
        PermissionView(SQL_QUERY_EXECUTE, "Executar SQL", "Permite executar consultas SQL de leitura"),
        PermissionView(EMAIL_SEND, "Enviar Email", "Permite envio imediato de relatorios por email"),
        PermissionView(EMAIL_TEST, "Enviar Email de Teste", "Permite envio de email de teste"),
        PermissionView(EMAIL_SCHEDULE_READ, "Visualizar Agendamentos", "Permite listar e consultar agendamentos"),
        PermissionView(EMAIL_SCHEDULE_WRITE, "Gerenciar Agendamentos", "Permite criar, editar, pausar e remover agendamentos")
    )

    val all = catalog.map { it.code }
}

data class PermissionView(
    val code: String,
    val label: String,
    val description: String
)
