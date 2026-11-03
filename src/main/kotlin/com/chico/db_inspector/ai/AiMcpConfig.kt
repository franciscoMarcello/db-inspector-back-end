package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.JsonSchemaValidator
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Servidor MCP em /api/db/ai/mcp, transporte HTTP sem estado (cada POST e um JSON-RPC completo).
 *
 * Fica sob /api/db/ai/ de proposito: a cadeia do Spring Security (inclusive o filtro que aceita token
 * de IA) ja protege o caminho, entao o MCP herda a mesma autenticacao da API REST. A autenticacao e
 * a URL publica sao lidas aqui e levadas no contexto do transporte, porque a ferramenta pode rodar
 * em outra thread.
 */
@Configuration
class AiMcpConfig {

    @Bean
    fun aiMcpJsonMapper(objectMapper: ObjectMapper): McpJsonMapper = JacksonMcpJsonMapper(objectMapper)

    @Bean
    fun aiMcpTransport(jsonMapper: McpJsonMapper, properties: DbInspectorProperties): HttpServletStatelessServerTransport =
        HttpServletStatelessServerTransport.builder()
            .jsonMapper(jsonMapper)
            .messageEndpoint(PATH)
            .contextExtractor { request ->
                val baseUrl = properties.ai.publicUrl.takeIf { it.isNotBlank() }
                    ?: (
                        "${request.scheme}://${request.serverName}" +
                            (if (request.serverPort in listOf(80, 443)) "" else ":${request.serverPort}") +
                            request.contextPath.trimEnd('/')
                        )
                val values = mutableMapOf<String, Any>(BASE_URL_KEY to baseUrl)
                SecurityContextHolder.getContext().authentication?.let { values[AUTHENTICATION_KEY] = it }
                McpTransportContext.create(values)
            }
            // Cliente MCP nao manda Origin; recusar quem manda fecha a porta para DNS rebinding.
            .securityValidator(DefaultServerTransportSecurityValidator.builder().build())
            .maxRequestSize(MAX_REQUEST_SIZE)
            .build()

    @Bean(destroyMethod = "close")
    fun aiMcpServer(
        transport: HttpServletStatelessServerTransport,
        jsonMapper: McpJsonMapper,
        tools: AiMcpTools
    ): McpStatelessSyncServer = McpServer.sync(transport)
        .jsonMapper(jsonMapper)
        .serverInfo("dbinspector-relatorios", "1.0")
        .instructions(INSTRUCTIONS)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).build())
        // As ferramentas validam os proprios argumentos (Bean Validation nos DTOs), como a API REST.
        .jsonSchemaValidator { _, _ -> JsonSchemaValidator.ValidationResponse.asValid(null) }
        .validateToolInputs(false)
        .tools(tools.specifications())
        .resources(tools.resources())
        .build()

    @Bean
    fun aiMcpServlet(transport: HttpServletStatelessServerTransport) =
        ServletRegistrationBean(transport, PATH).apply {
            setName("aiMcp")
            setLoadOnStartup(1)
        }

    companion object {
        const val PATH = "/api/db/ai/mcp"
        const val BASE_URL_KEY = "baseUrl"
        const val AUTHENTICATION_KEY = "authentication"

        /** Definicao com JRXML (ate 500 mil) e folga para o envelope JSON. */
        const val MAX_REQUEST_SIZE = 2 * 1024 * 1024

        const val INSTRUCTIONS =
            "Cria RASCUNHOS de relatorios sobre o banco PostgreSQL do AgroMobi (DB Inspector). Publicar e decisao " +
                "de uma pessoa. Antes de qualquer coisa chame `ler_instrucoes` sem argumento: traz o ciclo, as regras " +
                "do validador, o formato do JRXML e os limites. Ciclo: buscar_tabelas/descrever_tabela -> consultar " +
                "(testar hipoteses) -> validar ate valido=true -> criar_rascunho ou salvar_rascunho com nota -> previa " +
                "-> avisar a pessoa para publicar na tela."
    }
}
