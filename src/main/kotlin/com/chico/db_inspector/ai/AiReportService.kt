package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.report.ExecutionResult
import com.chico.dbinspector.report.ReportEntity
import com.chico.dbinspector.report.ReportFolderService
import com.chico.dbinspector.report.ReportJasperTemplateRepository
import com.chico.dbinspector.report.ReportJasperTemplateService
import com.chico.dbinspector.report.ReportPdfService
import com.chico.dbinspector.report.ReportQueryService
import com.chico.dbinspector.report.ReportResponse
import com.chico.dbinspector.report.ReportRunRequest
import com.chico.dbinspector.report.ReportService
import com.chico.dbinspector.report.ReportVariableEntity
import com.chico.dbinspector.report.ReportVariableRequest
import com.chico.dbinspector.report.ReportVariableService
import com.chico.dbinspector.report.ReportVersionContent
import com.chico.dbinspector.report.ReportVersionResponse
import com.chico.dbinspector.report.ReportVersionService
import com.chico.dbinspector.report.ReportVersionSummaryResponse
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

/**
 * Relatorios pelo lado do agente: validar (devolve todos os problemas), criar/atualizar rascunho,
 * previa com dados reais (poucas linhas) e previa do PDF. Publicar nao existe aqui.
 */
@Service
class AiReportService(
    private val reportService: ReportService,
    private val versionService: ReportVersionService,
    private val folderService: ReportFolderService,
    private val templateService: ReportJasperTemplateService,
    private val templateRepository: ReportJasperTemplateRepository,
    private val variableService: ReportVariableService,
    private val queryService: ReportQueryService,
    private val pdfService: ReportPdfService,
    private val guard: AiSqlGuard,
    private val jrxmlGuard: AiJrxmlGuard,
    private val limiter: AiQueryLimiter,
    private val approvals: AiApprovalService,
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties
) {
    private val placeholderRegex = Regex("(?<!:):([A-Za-z_][A-Za-z0-9_]*)")

    private companion object {
        const val MAX_PDF_CELL_CHARS = 2_000
    }

    fun folders(): List<AiFolder> = folderService.list().filterNot { it.archived }.map { AiFolder(it.id, it.name, it.description) }

    fun templates(): List<AiTemplateSummary> =
        templateService.list().map { AiTemplateSummary(it.id, it.name, it.description, it.archived) }

    fun template(id: UUID): AiTemplateDetail =
        templateService.get(id).let { AiTemplateDetail(it.id, it.name, it.description, it.jrxml) }

    fun reports(): List<AiReportSummary> = reportService.list().filterNot { it.archived }.map { report ->
        AiReportSummary(
            id = report.id,
            nome = report.name,
            descricao = report.description,
            pasta = report.folder?.name,
            rascunho = report.draft,
            rascunhoAberto = report.openDraftVersion,
            templateId = report.jasperTemplate?.id,
            variaveis = report.variables.map { it.key },
            atualizadoEm = report.updatedAt
        )
    }

    fun report(id: UUID): AiReportDetail {
        val report = reportService.get(id)
        val jrxml = report.jasperTemplate?.id?.let { runCatching { templateService.get(UUID.fromString(it)).jrxml }.getOrNull() }
        return AiReportDetail(report.id, report.draft, report.openDraftVersion, report.toDefinition(jrxml))
    }

    fun versions(id: UUID): List<ReportVersionSummaryResponse> = versionService.list(id)

    fun version(id: UUID, number: Int): AiReportDetail {
        val version = versionService.get(id, number)
        return AiReportDetail(id.toString(), version.status == "DRAFT", null, version.toDefinition())
    }

    fun validate(request: AiReportValidationRequest, ctx: UpstreamContext): AiReportValidationResponse =
        validateDefinition(request.definicao, request.parametrosTeste, ctx)

    fun createDraft(request: AiReportDraftRequest, ctx: UpstreamContext): AiReportDraftResponse {
        val validation = requireValid(request.definicao, request.parametrosTeste, ctx)
        val created = versionService.createDraftReport(request.definicao.toContent(), request.nota)
        return draftResponse(created, validation)
    }

    fun saveDraft(reportId: UUID, request: AiReportDraftRequest, ctx: UpstreamContext): AiReportDraftResponse {
        val validation = requireValid(request.definicao, request.parametrosTeste, ctx)
        val saved = versionService.saveDraft(reportId, request.definicao.toContent(), request.nota)
        return draftResponse(saved, validation)
    }

    fun preview(reportId: UUID, versionNumber: Int, request: AiPreviewRequest, ctx: UpstreamContext): AiPreviewResponse {
        requireAuthority("REPORT_RUN")
        val report = versionService.transientReport(reportId, versionNumber)
        val limit = (request.linhas ?: properties.ai.previewMaxRows).coerceIn(1, properties.ai.previewMaxRows)
        val rendered = render(report, request.parametros)
        val token = currentAiToken()
        val approved = token?.let { approvals.findActive(it.tokenId, rendered) } != null
        return limiter.run(token?.tokenId) {
            val plan = guard.check(rendered, ctx, ignoreCost = approved)
            val start = System.nanoTime()
            val (columns, rows) = execute("SELECT * FROM ($rendered) ai_previa LIMIT ${limit + 1}", ctx)
            AiPreviewResponse(
                colunas = columns,
                linhas = rows.take(limit),
                quantidade = minOf(rows.size, limit),
                truncado = rows.size > limit,
                custoEstimado = plan.totalCost,
                tempoMs = (System.nanoTime() - start) / 1_000_000
            )
        }
    }

    /** Gera o PDF da versao com poucas linhas. Devolve os bytes e quantas linhas usou. */
    fun pdf(reportId: UUID, versionNumber: Int, request: AiPreviewRequest, ctx: UpstreamContext): Pair<ByteArray, AiPdfPreviewResponse> {
        requireAuthority("REPORT_RUN")
        val report = versionService.transientReport(reportId, versionNumber)
        if (report.jasperTemplate?.jrxml == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A versao $versionNumber nao tem JRXML nem template")
        }
        // JRXML escrito na versao (pela IA) passa pela guarda; template so reaproveitado por id e de pessoa.
        versionService.get(reportId, versionNumber).jrxml?.let { authored ->
            jrxmlGuard.problems(authored).takeIf { it.isNotEmpty() }?.let { throw AiValidationException(it) }
        }

        val limit = properties.ai.pdfPreviewMaxRows
        val rendered = render(report, request.parametros)
        val token = currentAiToken()
        val approved = token?.let { approvals.findActive(it.tokenId, rendered) } != null
        return limiter.run(token?.tokenId) {
            guard.check(rendered, ctx, ignoreCost = approved)
            val start = System.nanoTime()
            val (columns, rows) = execute("SELECT * FROM ($rendered) ai_pdf LIMIT ${limit + 1}", ctx)
            val execution = ExecutionResult(
                query = rendered,
                columns = columns,
                // Texto de celula truncado: uma coluna enorme, copiada varias vezes por uma expressao, nao
                // pode esgotar a memoria. Valores reais de relatorio ficam muito abaixo disso.
                allRows = rows.take(limit).map { row -> row.mapValues { (_, value) -> truncateCell(value) } },
                elapsedMs = (System.nanoTime() - start) / 1_000_000
            )
            // A guarda estatica nao limita todo crescimento de texto (replace/format aninhados); a
            // contencao e aqui: alocacao grande falha no proprio thread e vira problema legivel.
            val bytes = try {
                pdfService.generatePdf(reportId, report, execution, ReportRunRequest(params = request.parametros, safe = true))
            } catch (error: OutOfMemoryError) {
                throw AiValidationException(listOf(AiProblem("jrxml", "jrxml_recursos", "O template consumiu memoria demais ao gerar o PDF")))
            } catch (error: StackOverflowError) {
                throw AiValidationException(listOf(AiProblem("jrxml", "jrxml_recursos", "O template aninhou expressoes demais ao gerar o PDF")))
            }
            bytes to AiPdfPreviewResponse(
                paginas = countPdfPages(bytes),
                tamanhoBytes = bytes.size,
                linhasUsadas = execution.allRows.size,
                truncado = rows.size > limit,
                download = "/api/db/ai/relatorios/$reportId/versoes/$versionNumber/pdf"
            )
        }
    }

    private fun truncateCell(value: Any?): Any? =
        if (value is String && value.length > MAX_PDF_CELL_CHARS) value.take(MAX_PDF_CELL_CHARS) else value

    /** Mesma regra do @PreAuthorize da API REST, aqui para valer tambem no MCP. */
    private fun requireAuthority(authority: String) {
        val granted = SecurityContextHolder.getContext().authentication?.authorities.orEmpty().any { it.authority == authority }
        if (!granted) throw AiRuleException(HttpStatus.FORBIDDEN, "proibido", "Esta acao exige a permissao $authority")
    }

    private fun requireValid(definition: AiReportDefinition, params: Map<String, Any?>, ctx: UpstreamContext): AiReportValidationResponse {
        val validation = validateDefinition(definition, params, ctx)
        if (!validation.valido) throw AiValidationException(validation.problemas)
        return validation
    }

    private fun draftResponse(version: ReportVersionResponse, validation: AiReportValidationResponse) = AiReportDraftResponse(
        relatorioId = version.reportId,
        versao = version.versionNumber,
        status = version.status,
        avisos = validation.avisos,
        mensagem = "Rascunho v${version.versionNumber} gravado. Publicar e decisao de uma pessoa, na tela de Relatorios."
    )

    private fun validateDefinition(
        definition: AiReportDefinition,
        params: Map<String, Any?>,
        ctx: UpstreamContext
    ): AiReportValidationResponse {
        val problems = mutableListOf<AiProblem>()
        val warnings = mutableListOf<AiProblem>()
        val sql = definition.sql.trim().removeSuffix(";").trim()

        val variables = try {
            variableService.normalizeVariables(definition.variaveis.map { it.toRequest() })
        } catch (ex: IllegalArgumentException) {
            problems += AiProblem("variaveis", "variavel_invalida", ex.message ?: "Variavel invalida")
            emptyList()
        }
        problems += guard.staticProblems(sql, "sql")
        problems += placeholderProblems(sql, variables, definition, warnings)
        variables.forEachIndexed { index, variable ->
            variable.optionsSql?.let { problems += guard.staticProblems(it, "variaveis[$index].sqlOpcoes") }
        }
        definition.sqlSap?.takeIf { it.isNotBlank() }?.let { problems += guard.staticProblems(it, "sqlSap") }
        if (!definition.chaveComparacao.isNullOrBlank() && definition.sqlSap.isNullOrBlank()) {
            problems += AiProblem("chaveComparacao", "comparacao_sem_sql_sap", "chaveComparacao exige sqlSap")
        }
        definition.templateId?.takeIf { !templateRepository.existsById(it) }?.let {
            problems += AiProblem("templateId", "template_inexistente", "Template $it nao encontrado")
        }
        val jrxml = definition.jrxml?.takeIf { it.isNotBlank() }
        jrxml?.let { problems += jrxmlGuard.problems(it) }
        val templateFields = jrxml?.let { jrxmlGuard.fieldNames(it) }.orEmpty()

        // Com problema estatico, nao vale a pena ir ao banco.
        if (problems.isNotEmpty()) {
            return AiReportValidationResponse(false, problems.distinct(), warnings, emptyList(), templateFields, null)
        }

        val rendered = try {
            queryService.buildQueryWithParams(sql, variables, params, enforceRequired = false)
        } catch (ex: IllegalArgumentException) {
            problems += AiProblem("parametrosTeste", "parametro_invalido", ex.message ?: "Parametro invalido")
            return AiReportValidationResponse(false, problems, warnings, emptyList(), templateFields, null)
        }

        var cost: Double? = null
        var columns = emptyList<String>()
        limiter.run(currentAiToken()?.tokenId) {
            try {
                val plan = guard.explain(rendered, ctx)
                cost = plan.totalCost
                guard.planProblems(plan, ignoreCost = true).let { problems += it }
                plan.totalCost?.takeIf { it > properties.ai.maxPlanCost }?.let {
                    warnings += AiProblem(
                        "sql", "custo_alto",
                        "Custo estimado ${"%.0f".format(it)} com os parametros de teste. Confira se os filtros limitam o volume."
                    )
                }
                if (problems.isEmpty()) {
                    columns = execute("SELECT * FROM ($rendered) ai_colunas LIMIT 0", ctx).first
                }
            } catch (ex: AiValidationException) {
                problems += ex.problems
            }
            variables.forEachIndexed { index, variable ->
                variable.optionsSql?.let { optionsSql -> problems += optionsProblems(optionsSql, variables, params, index, ctx) }
            }
        }

        if (columns.isEmpty() && problems.isEmpty()) {
            warnings += AiProblem("sql", "colunas_nao_verificadas", "O banco nao informou as colunas; campos do JRXML nao foram conferidos")
        }
        if (columns.isNotEmpty()) {
            (templateFields - columns.toSet()).takeIf { it.isNotEmpty() }?.let { missing ->
                problems += AiProblem(
                    "jrxml", "campo_sem_coluna",
                    "Campos do JRXML que o SQL nao devolve: ${missing.joinToString(", ")}. Colunas do SQL: ${columns.joinToString(", ")}"
                )
            }
            definition.chaveComparacao?.takeIf { it.isNotBlank() && it !in columns }?.let {
                problems += AiProblem("chaveComparacao", "chave_sem_coluna", "A coluna '$it' nao existe no resultado do SQL")
            }
        }
        if (jrxml != null && problems.isEmpty()) {
            problems += jrxmlGuard.compile(jrxml).second
        }
        return AiReportValidationResponse(problems.isEmpty(), problems.distinct(), warnings, columns, templateFields, cost)
    }

    private fun placeholderProblems(
        sql: String,
        variables: List<ReportVariableEntity>,
        definition: AiReportDefinition,
        warnings: MutableList<AiProblem>
    ): List<AiProblem> {
        val problems = mutableListOf<AiProblem>()
        val placeholders = placeholderRegex.findAll(sql).map { it.groupValues[1] }.toSet()
        val keys = variables.map { it.key }.toSet()
        (placeholders - keys).forEach {
            problems += AiProblem("sql", "placeholder_sem_variavel", "':$it' nao tem variavel declarada em variaveis[]")
        }
        val usedElsewhere = listOfNotNull(definition.sqlSap, *variables.mapNotNull { it.optionsSql }.toTypedArray())
            .flatMap { text -> placeholderRegex.findAll(text).map { it.groupValues[1] }.toList() }.toSet()
        (keys - placeholders - usedElsewhere).forEach {
            warnings += AiProblem("variaveis", "variavel_nao_usada", "A variavel '$it' nao aparece no SQL")
        }
        variables.filter { it.multiple && it.key in placeholders }.forEach { variable ->
            val key = Regex.escape(variable.key)
            when {
                Regex("\\bin\\s*\\(\\s*:$key\\s*\\)", RegexOption.IGNORE_CASE).containsMatchIn(sql) ->
                    problems += AiProblem("sql", "variavel_multipla", "Use 'IN :${variable.key}' sem parenteses: o servidor ja os coloca")
                !Regex("\\bin\\s*:$key\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) ->
                    problems += AiProblem("sql", "variavel_multipla", "Variavel multipla '${variable.key}' deve ser usada com 'IN :${variable.key}'")
            }
        }
        return problems
    }

    private fun optionsProblems(
        optionsSql: String,
        variables: List<ReportVariableEntity>,
        params: Map<String, Any?>,
        index: Int,
        ctx: UpstreamContext
    ): List<AiProblem> {
        val path = "variaveis[$index].sqlOpcoes"
        return try {
            val rendered = queryService.buildQueryWithParams(optionsSql.trim().removeSuffix(";"), variables, params, enforceRequired = false)
            val plan = guard.explain(rendered, ctx, path)
            val problems = guard.planProblems(plan, path, ignoreCost = true).toMutableList()
            if (problems.isEmpty()) {
                val columns = execute("SELECT * FROM ($rendered) ai_opcoes LIMIT 0", ctx).first.map { it.lowercase() }.toSet()
                if (columns.isNotEmpty() && columns != setOf("valor", "descricao")) {
                    problems += AiProblem(path, "opcoes_colunas", "SQL de opcoes deve devolver exatamente as colunas 'valor' e 'descricao'")
                }
            }
            problems
        } catch (ex: AiValidationException) {
            ex.problems
        } catch (ex: IllegalArgumentException) {
            listOf(AiProblem(path, "parametro_invalido", ex.message ?: "Parametro invalido"))
        }
    }

    private fun render(report: ReportEntity, params: Map<String, Any?>): String = try {
        queryService.buildQueryWithParams(report.sql.trim().removeSuffix(";"), report.variables, params, enforceRequired = true)
    } catch (ex: IllegalArgumentException) {
        throw AiValidationException(listOf(AiProblem("parametros", "parametro_invalido", ex.message ?: "Parametro invalido")))
    }

    private fun execute(query: String, ctx: UpstreamContext): Pair<List<String>, List<Map<String, Any?>>> = try {
        tabularResult(
            sqlExecClient.execWithTimeout(ctx.endpointUrl, ctx.bearer, query, Duration.ofSeconds(properties.ai.queryTimeoutSeconds))
        )
    } catch (ex: Exception) {
        throw AiValidationException(listOf(AiProblem("sql", "erro_no_banco", upstreamErrorMessage(ex))))
    }

    private fun countPdfPages(bytes: ByteArray): Int =
        Regex("/Type\\s*/Page(?!s)").findAll(String(bytes, Charsets.ISO_8859_1)).count()

    private fun AiReportVariable.toRequest() = ReportVariableRequest(
        key = chave.trim(),
        label = rotulo.trim(),
        type = tipo.trim(),
        required = obrigatoria,
        multiple = multipla,
        defaultValue = padrao,
        optionsSql = sqlOpcoes
    )

    private fun AiReportDefinition.toContent() = ReportVersionContent(
        name = nome,
        description = descricao,
        folderId = pastaId,
        sql = sql,
        secondSql = sqlSap,
        comparisonKey = chaveComparacao,
        variables = variaveis.map { it.toRequest() },
        jasperTemplateId = templateId,
        jrxml = jrxml
    )

    private fun ReportVariableRequest.toAi() = AiReportVariable(
        chave = key,
        rotulo = label,
        tipo = type,
        obrigatoria = required,
        multipla = multiple,
        padrao = defaultValue,
        sqlOpcoes = optionsSql
    )

    private fun ReportResponse.toDefinition(jrxml: String?) = AiReportDefinition(
        nome = name,
        descricao = description,
        pastaId = folder?.id?.let(UUID::fromString),
        sql = sql,
        sqlSap = secondSql,
        chaveComparacao = comparisonKey,
        variaveis = variables.sortedBy { it.orderIndex }.map {
            AiReportVariable(it.key, it.label, it.type, it.required, it.multiple, it.defaultValue, it.optionsSql)
        },
        templateId = jasperTemplate?.id?.let(UUID::fromString),
        jrxml = jrxml
    )

    private fun ReportVersionResponse.toDefinition() = AiReportDefinition(
        nome = name,
        descricao = description,
        pastaId = folderId?.let(UUID::fromString),
        sql = sql,
        sqlSap = secondSql,
        chaveComparacao = comparisonKey,
        variaveis = variables.map { it.toAi() },
        templateId = jasperTemplateId?.let(UUID::fromString),
        jrxml = jrxml
    )
}
