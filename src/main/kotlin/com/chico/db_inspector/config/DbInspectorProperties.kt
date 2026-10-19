package com.chico.dbinspector.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("dbinspector")
data class DbInspectorProperties(
    var sqlExecBaseUrl: String = "",
    var apitoken: String = "",
    var environment: String = "Prod",
    var mail: MailProperties = MailProperties(),
    var schedule: ScheduleProperties = ScheduleProperties(),
    var reports: ReportsProperties = ReportsProperties(),
    var security: SecurityProperties = SecurityProperties(),
    var ai: AiProperties = AiProperties()
) {
    data class AiProperties(
        var tokenMaxDays: Int = 90,
        var tokenDefaultDays: Int = 30,
        var allowedSchemas: List<String> = listOf(
            "agro", "agricola", "confinamento", "contabil", "contrato", "estoque",
            "fiscal", "frota", "produto", "reproducao", "sap"
        ),
        /**
         * Tabelas liberadas uma a uma, fora dos schemas acima. Cadastros de apoio (parceiro, empresa,
         * cidade, centro de custo...). Fora de proposito: br.funcionario (senha_web, RH),
         * br.endereco_pessoa (endereco de pessoas) e o resto do public (usuarios, permissoes, logs).
         */
        var allowedTables: List<String> = listOf(
            "public.res_partner", "public.res_company", "public.res_city", "public.res_country_state",
            "public.res_currency", "br.centro_custo", "br.departamento", "br.banco", "br.cnae",
            "br.calendario", "br.calendario_feriado", "br.local_trabalho", "br.localidade", "br.mes",
            "br.orgao_emissor"
        ),
        /**
         * Colunas que a IA nunca le, em qualquer tabela liberada: segredos, conta bancaria, contato e
         * data de nascimento. Valem no texto do SQL e na saida do plano (pega o SELECT *).
         */
        var blockedColumns: List<String> = listOf(
            "senha_web", "site_password", "nfe_certificado_senha", "birthdate", "conta_num", "conta_dv",
            "cc_cpf_titular", "cc_cpf_titular_2", "email", "phone"
        ),
        /** Alem dos nomes acima: qualquer coluna cujo nome case com este padrao (sem diferenciar maiusculas). */
        var blockedColumnPattern: String = ".*(senha|password|passwd|secret|token|certificado).*",
        /** Funcoes definidas no banco (fora de pg_catalog) liberadas mesmo assim (ex.: unaccent). */
        var allowedFunctions: List<String> = emptyList(),
        var queryMaxRows: Int = 200,
        var previewMaxRows: Int = 50,
        var queriesPerMinute: Int = 30,
        var maxConcurrentQueries: Int = 1,
        var maxPlanCost: Double = 1_000_000.0,
        var approvalMaxRows: Int = 5_000,
        var approvalTtlMinutes: Long = 60,
        var pdfPreviewMaxRows: Int = 200,
        var queryTimeoutSeconds: Long = 60,
        var explainTimeoutSeconds: Long = 20,
        var approvalMaxTtlMinutes: Long = 1440,
        /** Endereco do backend que vai nas instrucoes; vazio = deduzido da requisicao. */
        var publicUrl: String = "",
        /**
         * Pasta FORA do repositorio com o contexto do banco: catalogo.json (estrutura das tabelas) e
         * glossario/ (regras de negocio). Nao vao para o git; em producao e um volume montado.
         */
        var contextPath: String = ""
    )

    data class MailProperties(
        var from: String = "no-reply@dbinspector.local",
        var passwordResetUrl: String = ""
    )

    data class ScheduleProperties(
        var previewLimit: Int = 200,
        var attachmentRowLimit: Int = 5_000,
        var attachmentSizeLimitBytes: Long = 5 * 1024 * 1024,
        var sqlTimeoutMs: Long = 15_000,
        var timeZone: String = "America/Porto_Velho"
    )

    data class ReportsProperties(
        var maxRows: Int = 500
    )

    data class SecurityProperties(
        var aclDefaultDeny: Boolean = false,
        var loginMaxAttempts: Int = 5,
        var loginWindowSeconds: Int = 60
    )
}
