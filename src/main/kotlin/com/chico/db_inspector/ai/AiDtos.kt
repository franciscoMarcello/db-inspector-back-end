package com.chico.dbinspector.ai

import com.chico.dbinspector.auth.AuthUserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

data class AiTokenCreateRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    val validityDays: Int? = null
)

data class AiTokenCreatedResponse(
    val id: String,
    val name: String,
    val token: String,
    val expiresAt: Long,
    val warning: String
)

data class AiTokenResponse(
    val id: String,
    val name: String,
    val prefix: String,
    val createdBy: String,
    val createdAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
    val lastUsedAt: Long?,
    val active: Boolean,
    val expired: Boolean
)

/** Quem e o token por tras da requisicao: vai nos detalhes da autenticacao. */
data class AiTokenIdentity(
    val principal: AuthUserPrincipal,
    val tokenId: UUID,
    val tokenName: String
)

data class AiTokenAuthenticationDetails(
    val tokenId: UUID,
    val tokenName: String
)

/** Token de IA da requisicao atual, ou null se quem chama e uma pessoa. */
fun currentAiToken(): AiTokenAuthenticationDetails? =
    SecurityContextHolder.getContext().authentication?.details as? AiTokenAuthenticationDetails

data class AiQueryRequest(
    @field:NotBlank
    @field:Size(max = 100_000)
    val sql: String,
    val limite: Int? = null
)

data class AiQueryResponse(
    val colunas: List<String>,
    val linhas: List<Map<String, Any?>>,
    val quantidade: Int,
    val truncado: Boolean,
    val limiteAplicado: Int,
    val custoEstimado: Double?,
    val tempoMs: Long,
    val liberacaoId: String?,
    val aviso: String?
)

data class AiExplainRequest(
    @field:NotBlank
    @field:Size(max = 100_000)
    val sql: String
)

data class AiExplainResponse(
    val custoEstimado: Double?,
    val limiteDeCusto: Double,
    val schemas: Set<String>,
    val tabelas: Set<String>,
    val problemas: List<AiProblem>,
    val plano: Any?
)

data class AiApprovalRequest(
    @field:NotBlank
    @field:Size(max = 100_000)
    val sql: String,
    @field:NotBlank
    @field:Size(max = 2_000)
    val motivo: String,
    val linhas: Int? = null
)

data class AiApprovalDecisionRequest(
    val linhas: Int? = null,
    val minutos: Long? = null,
    @field:Size(max = 2_000)
    val nota: String? = null
)

data class AiApprovalResponse(
    val id: String,
    val status: String,
    val sql: String,
    val motivo: String,
    val linhasPedidas: Int,
    val linhasAprovadas: Int?,
    val custoEstimado: Double?,
    val token: String,
    val solicitadoPor: String,
    val decididoPor: String?,
    val decididoEm: Long?,
    val nota: String?,
    val expiraEm: Long?,
    val criadoEm: Long,
    val vigente: Boolean
)

data class AiReportVariable(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$")
    val chave: String,
    @field:NotBlank
    val rotulo: String,
    @field:NotBlank
    val tipo: String,
    val obrigatoria: Boolean = true,
    val multipla: Boolean = false,
    val padrao: String? = null,
    val sqlOpcoes: String? = null
)

/** Definicao de relatorio no formato da IA. `templateName` (legado) e preenchido pelo servidor. */
data class AiReportDefinition(
    @field:NotBlank
    @field:Size(max = 200)
    val nome: String,
    @field:Size(max = 4_000)
    val descricao: String? = null,
    val pastaId: UUID? = null,
    @field:NotBlank
    @field:Size(max = 100_000)
    val sql: String,
    @field:Size(max = 100_000)
    val sqlSap: String? = null,
    @field:Size(max = 128)
    val chaveComparacao: String? = null,
    @field:Valid
    val variaveis: List<AiReportVariable> = emptyList(),
    val templateId: UUID? = null,
    @field:Size(max = 500_000)
    val jrxml: String? = null
)

data class AiReportValidationRequest(
    @field:Valid
    val definicao: AiReportDefinition,
    val parametrosTeste: Map<String, Any?> = emptyMap()
)

data class AiReportDraftRequest(
    @field:Valid
    val definicao: AiReportDefinition,
    @field:Size(max = 8_000)
    val nota: String? = null,
    val parametrosTeste: Map<String, Any?> = emptyMap()
)

data class AiReportValidationResponse(
    val valido: Boolean,
    val problemas: List<AiProblem>,
    val avisos: List<AiProblem>,
    val colunas: List<String>,
    val camposDoTemplate: List<String>,
    val custoEstimado: Double?
)

data class AiReportDraftResponse(
    val relatorioId: String,
    val versao: Int,
    val status: String,
    val avisos: List<AiProblem>,
    val mensagem: String
)

data class AiPreviewRequest(
    val parametros: Map<String, Any?> = emptyMap(),
    val linhas: Int? = null
)

data class AiPreviewResponse(
    val colunas: List<String>,
    val linhas: List<Map<String, Any?>>,
    val quantidade: Int,
    val truncado: Boolean,
    val custoEstimado: Double?,
    val tempoMs: Long
)

data class AiPdfPreviewResponse(
    val paginas: Int,
    val tamanhoBytes: Int,
    val linhasUsadas: Int,
    val truncado: Boolean,
    val download: String
)

data class AiFolder(val id: String, val nome: String, val descricao: String?)

data class AiTemplateSummary(val id: String, val nome: String, val descricao: String?, val arquivado: Boolean)

data class AiTemplateDetail(val id: String, val nome: String, val descricao: String?, val jrxml: String)

data class AiReportSummary(
    val id: String,
    val nome: String,
    val descricao: String?,
    val pasta: String?,
    val rascunho: Boolean,
    val rascunhoAberto: Int?,
    val templateId: String?,
    val variaveis: List<String>,
    val atualizadoEm: Long
)

data class AiReportDetail(
    val id: String,
    val rascunho: Boolean,
    val rascunhoAberto: Int?,
    val definicao: AiReportDefinition
)
