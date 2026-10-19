package com.chico.dbinspector.ai

import com.chico.dbinspector.web.UpstreamContextFactory
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Ferramentas MCP: as mesmas operacoes da API REST de /api/db/ai, chamando os mesmos services.
 * Nao existem, de proposito: publicar, excluir, restaurar versao, gerenciar tokens e liberar consulta.
 */
@Component
class AiMcpTools(
    private val reportService: AiReportService,
    private val catalogService: AiCatalogService,
    private val queryService: AiQueryService,
    private val approvalService: AiApprovalService,
    private val instructions: AiInstructionsService,
    private val upstream: UpstreamContextFactory,
    private val mcpJson: McpJsonMapper,
    private val objectMapper: ObjectMapper,
    private val validator: Validator
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun specifications(): List<SyncToolSpecification> = listOf(
        tool(
            "ler_instrucoes",
            "Instrucoes oficiais: ciclo, regras do validador de SQL e JRXML, limites. Chame SEM argumento antes de " +
                "criar ou alterar relatorio. Com `caminho`, le glossario ou exemplo (ex.: glossario/README.md, " +
                "exemplos/<nome>/definicao.json).",
            """{"type":"object","properties":{"caminho":{"type":"string","enum":${objectMapper.writeValueAsString(instructions.paths())}}},"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_READ"
        ) { context, args -> text(instructions.read(args["caminho"] as? String, baseUrl(context))) },
        tool("listar_schemas", "Schemas liberados para IA e quantas tabelas cada um tem.", NO_ARGS, readOnly = true, authority = "REPORT_READ") { _, _ ->
            json(mapOf("geradoEm" to catalogService.generatedAt(), "schemas" to catalogService.schemas()))
        },
        tool(
            "buscar_tabelas",
            "Busca tabelas pelo nome da tabela ou de colunas (ex.: 'colheita umidade'). Nao adivinhe nomes: busque.",
            """{"type":"object","properties":{"busca":{"type":"string"},"schema":{"type":"string"},"limite":{"type":"integer"}},"required":["busca"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_READ"
        ) { _, args -> json(catalogService.search(string(args, "busca"), args["schema"] as? String, int(args, "limite") ?: 30)) },
        tool(
            "descrever_tabela",
            "Colunas (conferidas ao vivo no banco), PK, FKs nos dois sentidos e linhas estimadas de uma tabela.",
            """{"type":"object","properties":{"schema":{"type":"string"},"tabela":{"type":"string"}},"required":["schema","tabela"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_READ"
        ) { _, args -> json(catalogService.describe(string(args, "schema"), string(args, "tabela"), upstream.create())) },
        tool(
            "consultar",
            "Executa um SELECT exploratorio para testar hipotese (status, JOIN que multiplica linha, FK nula, total). " +
                "Nada e gravado. Poucas linhas: prefira agregar.",
            """{"type":"object","properties":{"sql":{"type":"string"},"limite":{"type":"integer"}},"required":["sql"],"additionalProperties":false}""",
            readOnly = true
        ) { _, args -> json(queryService.query(convert(args, AiQueryRequest::class.java), upstream.create())) },
        tool(
            "explicar",
            "Plano e custo estimado de um SELECT (EXPLAIN, sem executar). Use para comparar faixa curta e longa.",
            """{"type":"object","properties":{"sql":{"type":"string"}},"required":["sql"],"additionalProperties":false}""",
            readOnly = true
        ) { _, args -> json(queryService.explain(convert(args, AiExplainRequest::class.java), upstream.create())) },
        tool("listar_pastas", "Pastas onde o relatorio pode ficar (pastaId).", NO_ARGS, readOnly = true, authority = "REPORT_READ") { _, _ ->
            json(reportService.folders())
        },
        tool("listar_templates", "Templates Jasper existentes, sem o JRXML.", NO_ARGS, readOnly = true, authority = "TEMPLATE_READ") { _, _ ->
            json(reportService.templates())
        },
        tool("obter_template", "Template Jasper com o JRXML, para reaproveitar.", ID_ARG, readOnly = true, authority = "TEMPLATE_READ") { _, args ->
            json(reportService.template(uuid(args, "id")))
        },
        tool("listar_relatorios", "Relatorios existentes, com pasta, variaveis e rascunho aberto.", NO_ARGS, readOnly = true, authority = "REPORT_READ") { _, _ ->
            json(reportService.reports())
        },
        tool(
            "obter_relatorio",
            "Definicao publicada completa de um relatorio (SQL, variaveis, JRXML), pronta para alterar e salvar como rascunho.",
            ID_ARG, readOnly = true, authority = "REPORT_READ"
        ) { _, args -> json(reportService.report(uuid(args, "id"))) },
        tool("listar_versoes", "Historico de versoes de um relatorio.", ID_ARG, readOnly = true, authority = "REPORT_READ") { _, args ->
            json(reportService.versions(uuid(args, "id")))
        },
        tool(
            "obter_versao", "Definicao de uma versao especifica.",
            """{"type":"object","properties":{"id":{"type":"string"},"versao":{"type":"integer"}},"required":["id","versao"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_READ"
        ) { _, args -> json(reportService.version(uuid(args, "id"), requiredInt(args, "versao"))) },
        tool(
            "validar",
            "Valida a definicao SEM gravar: SQL, variaveis, opcoes, schemas, JRXML (seguranca, campos x colunas, " +
                "compilacao). Devolve TODOS os problemas de uma vez. Repita ate valido=true.",
            """{"type":"object","properties":{"definicao":$DEFINITION_SCHEMA,"parametrosTeste":{"type":"object"}},"required":["definicao"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_WRITE"
        ) { _, args -> json(reportService.validate(convert(args, AiReportValidationRequest::class.java), upstream.create())) },
        tool(
            "criar_rascunho",
            "Cria um relatorio NOVO como rascunho (so quem edita ve). Valida antes de gravar. Na `nota`, escreva as " +
                "hipoteses testadas e os numeros de conferencia para quem vai publicar.",
            """{"type":"object","properties":{"definicao":$DEFINITION_SCHEMA,"nota":{"type":"string"},"parametrosTeste":{"type":"object"}},"required":["definicao"],"additionalProperties":false}""",
            authority = "REPORT_WRITE"
        ) { _, args -> json(reportService.createDraft(convert(args, AiReportDraftRequest::class.java), upstream.create())) },
        tool(
            "salvar_rascunho",
            "Grava nova versao (rascunho) de um relatorio existente. A publicada continua valendo ate uma pessoa publicar.",
            """{"type":"object","properties":{"id":{"type":"string"},"definicao":$DEFINITION_SCHEMA,"nota":{"type":"string"},"parametrosTeste":{"type":"object"}},"required":["id","definicao"],"additionalProperties":false}""",
            authority = "REPORT_WRITE"
        ) { _, args ->
            json(reportService.saveDraft(uuid(args, "id"), convert(args - "id", AiReportDraftRequest::class.java), upstream.create()))
        },
        tool(
            "previa",
            "Executa uma versao (inclusive rascunho) com dados reais e devolve poucas linhas.",
            """{"type":"object","properties":{"id":{"type":"string"},"versao":{"type":"integer"},"parametros":{"type":"object"},"linhas":{"type":"integer"}},"required":["id","versao"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_RUN"
        ) { _, args ->
            json(reportService.preview(uuid(args, "id"), requiredInt(args, "versao"), previewRequest(args), upstream.create()))
        },
        tool(
            "previa_pdf",
            "Gera o PDF da versao com poucas linhas e devolve paginas, tamanho e o caminho para baixar.",
            """{"type":"object","properties":{"id":{"type":"string"},"versao":{"type":"integer"},"parametros":{"type":"object"}},"required":["id","versao"],"additionalProperties":false}""",
            readOnly = true, authority = "REPORT_RUN"
        ) { _, args ->
            json(reportService.pdf(uuid(args, "id"), requiredInt(args, "versao"), previewRequest(args), upstream.create()).second)
        },
        tool(
            "solicitar_liberacao",
            "Pede a uma pessoa para passar dos limites (linhas/custo) num SQL especifico. Avise a pessoa e acompanhe " +
                "com consultar_liberacao; aprovado, repita o MESMO SQL.",
            """{"type":"object","properties":{"sql":{"type":"string"},"motivo":{"type":"string"},"linhas":{"type":"integer"}},"required":["sql","motivo"],"additionalProperties":false}"""
        ) { _, args -> json(approvalService.request(convert(args, AiApprovalRequest::class.java))) },
        tool("consultar_liberacao", "Situacao de um pedido de liberacao.", ID_ARG, readOnly = true) { _, args ->
            json(approvalService.getForToken(uuid(args, "id")))
        }
    )

    fun resources(): List<SyncResourceSpecification> = instructions.paths().map { path ->
        val uri = "dbinspector://instrucoes/$path"
        val mime = when {
            path.endsWith(".md") -> "text/markdown"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".jrxml") -> "application/xml"
            else -> "text/plain"
        }
        SyncResourceSpecification(
            McpSchema.Resource.builder().uri(uri).name(path).mimeType(mime).description("Instrucoes para IA: $path").build()
        ) { context, _ ->
            McpSchema.ReadResourceResult(listOf(McpSchema.TextResourceContents(uri, mime, instructions.read(path, baseUrl(context)))))
        }
    }

    private fun tool(
        name: String,
        description: String,
        schema: String,
        readOnly: Boolean = false,
        authority: String = "REPORT_RUN",
        execute: (McpTransportContext, Map<String, Any?>) -> CallToolResult
    ) = SyncToolSpecification(
        McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(mcpJson, schema)
            .annotations(McpSchema.ToolAnnotations(null, readOnly, false, readOnly, false, null))
            .build()
    ) { context, request ->
        withAuthentication(context, authority) {
            translateErrors(name) { execute(context, request.arguments().orEmpty()) }
        }
    }

    /** Restaura quem chamou (a ferramenta pode rodar fora da thread da requisicao) e confere a permissao. */
    private fun withAuthentication(context: McpTransportContext, authority: String, block: () -> CallToolResult): CallToolResult {
        val authentication = context.get(AiMcpConfig.AUTHENTICATION_KEY) as? Authentication
            ?: return error("nao_autenticado", "Envie o cabecalho Authorization: Bearer rpt_...")
        if (authentication.authorities.none { it.authority == authority }) {
            return error("proibido", "Esta ferramenta exige a permissao $authority")
        }
        val previous = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication })
        return try {
            block()
        } finally {
            SecurityContextHolder.setContext(previous)
        }
    }

    /** Erro de negocio volta legivel para a IA corrigir; erro inesperado vai para o log, sem detalhe interno. */
    private fun translateErrors(name: String, block: () -> CallToolResult): CallToolResult = try {
        block()
    } catch (ex: AiValidationException) {
        errorJson(mapOf("erro" to "validacao_falhou", "problemas" to ex.problems))
    } catch (ex: AiRuleException) {
        errorJson(mapOf("erro" to ex.regra, "mensagem" to ex.message, "dica" to ex.dica))
    } catch (ex: ResponseStatusException) {
        error("erro_${ex.statusCode.value()}", ex.reason ?: "Erro")
    } catch (ex: AccessDeniedException) {
        error("proibido", "Sem permissao para esta acao")
    } catch (ex: IllegalArgumentException) {
        error("requisicao_invalida", ex.message ?: "Argumentos invalidos")
    } catch (ex: Exception) {
        log.error("Falha inesperada na ferramenta MCP {}", name, ex)
        error("erro_interno", "Falha inesperada. Tente de novo; se repetir, avise a pessoa.")
    }

    private fun <T : Any> convert(args: Map<String, Any?>, type: Class<T>): T {
        val value = try {
            objectMapper.convertValue(args, type)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("Argumentos invalidos: ${ex.message?.substringBefore(" (through")?.take(300)}")
        }
        val violations = validator.validate(value)
        if (violations.isNotEmpty()) {
            throw AiValidationException(
                violations.map { AiProblem(it.propertyPath.toString(), "campo_invalido", it.message) }
            )
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun previewRequest(args: Map<String, Any?>) = AiPreviewRequest(
        parametros = (args["parametros"] as? Map<String, Any?>).orEmpty(),
        linhas = int(args, "linhas")
    )

    private fun baseUrl(context: McpTransportContext): String = context.get(AiMcpConfig.BASE_URL_KEY) as? String ?: ""

    private fun string(args: Map<String, Any?>, key: String): String =
        (args[key] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Informe '$key'")

    private fun int(args: Map<String, Any?>, key: String): Int? = when (val value = args[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun requiredInt(args: Map<String, Any?>, key: String): Int = int(args, key) ?: throw IllegalArgumentException("Informe '$key'")

    private fun uuid(args: Map<String, Any?>, key: String): UUID =
        runCatching { UUID.fromString(string(args, key)) }.getOrElse { throw IllegalArgumentException("'$key' deve ser um UUID") }

    private fun json(value: Any): CallToolResult = text(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))

    private fun text(content: String): CallToolResult = CallToolResult.builder().addTextContent(content).build()

    private fun error(code: String, message: String): CallToolResult = errorJson(mapOf("erro" to code, "mensagem" to message))

    private fun errorJson(body: Map<String, Any?>): CallToolResult =
        CallToolResult.builder().addTextContent(objectMapper.writeValueAsString(body)).isError(true).build()

    private companion object {
        const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""
        const val ID_ARG = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""
        const val DEFINITION_SCHEMA = """{"type":"object","required":["nome","sql"],"properties":{
            "nome":{"type":"string"},"descricao":{"type":"string"},"pastaId":{"type":"string"},
            "sql":{"type":"string","description":"SELECT/WITH PostgreSQL sem comentarios, tabelas com schema, filtros como :nome"},
            "sqlSap":{"type":"string"},"chaveComparacao":{"type":"string"},
            "variaveis":{"type":"array","items":{"type":"object","required":["chave","rotulo","tipo"],"properties":{
                "chave":{"type":"string"},"rotulo":{"type":"string"},
                "tipo":{"type":"string","enum":["string","number","date","datetime","boolean"]},
                "obrigatoria":{"type":"boolean"},"multipla":{"type":"boolean"},"padrao":{"type":"string"},
                "sqlOpcoes":{"type":"string","description":"SELECT que devolve valor e descricao"}}}},
            "templateId":{"type":"string"},"jrxml":{"type":"string"}}}"""
    }
}
