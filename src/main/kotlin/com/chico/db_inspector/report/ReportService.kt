package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.email.EmailReportFormatter
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.util.ReadOnlySqlValidator
import com.chico.dbinspector.web.UpstreamContext
import net.sf.jasperreports.engine.DefaultJasperReportsContext
import net.sf.jasperreports.engine.JRField
import net.sf.jasperreports.engine.JRException
import net.sf.jasperreports.engine.JRParameter
import net.sf.jasperreports.engine.JRPropertiesUtil
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperExportManager
import net.sf.jasperreports.engine.JasperFillManager
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.UUID

@Service
class ReportService(
    private val repository: ReportRepository,
    private val folderRepository: ReportFolderRepository,
    private val jasperTemplateRepository: ReportJasperTemplateRepository,
    private val accessControl: ReportAccessControlService,
    private val sqlExecClient: SqlExecClient,
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    companion object {
        private const val LOGO_RESOURCE_PATH = "reports/logo.png"
        private const val JASPER_JDT_COMPILER = "net.sf.jasperreports.jdt.JRJdtCompiler"
    }

    private val log = LoggerFactory.getLogger(ReportService::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateTimeSqlFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val allowedVariableTypes = setOf("string", "number", "date", "datetime", "boolean")
    private val optionColumnValor = "valor"
    private val optionColumnDescricao = "descricao"
    private val defaultFilterOptionsLimit = 100
    private val placeholderRegex = Regex("(?<!:):([A-Za-z_][A-Za-z0-9_]*)")
    private val writeKeywordsRegex =
        Regex("\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke)\\b", RegexOption.IGNORE_CASE)

    fun list(): List<ReportResponse> =
        repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
            .filter { accessControl.canViewReport(it) }
            .map { it.toResponse() }

    fun create(request: ReportRequest): ReportResponse {
        request.folderId?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }
        val entity = ReportEntity(
            name = request.name.trim(),
            templateName = request.templateName.trim(),
            sql = request.sql.trim(),
            description = request.description?.trim().takeUnless { it.isNullOrBlank() },
            archived = request.archived ?: false,
            folder = resolveFolder(request.folderId),
            jasperTemplate = resolveJasperTemplate(request.jasperTemplateId)
        )
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(entity.sql)
        entity.replaceVariables(normalizeVariables(request.variables))
        return repository.save(entity).toResponse()
    }

    fun update(id: UUID, request: ReportRequest): ReportResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.EDIT)
        request.folderId?.let { accessControl.requireFolderAccess(it, AccessAction.EDIT) }
        entity.name = request.name.trim()
        entity.templateName = request.templateName.trim()
        entity.sql = request.sql.trim()
        entity.description = request.description?.trim().takeUnless { it.isNullOrBlank() }
        request.archived?.let { entity.archived = it }
        entity.folder = resolveFolder(request.folderId)
        entity.jasperTemplate = resolveJasperTemplate(request.jasperTemplateId)
        require(entity.sql.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(entity.sql)
        entity.replaceVariables(normalizeVariables(request.variables))
        return repository.save(entity).toResponse()
    }

    fun delete(id: UUID) {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.DELETE)
        repository.deleteById(id)
    }

    fun run(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ReportRunResponse {
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val execution = executeReport(entity, ctx, request.params)

        val totalRows = execution.allRows.size
        val maxRows = properties.reports.maxRows
        val truncated = totalRows > maxRows
        val rows = if (truncated) execution.allRows.take(maxRows) else execution.allRows
        val summaries = computeSummaries(execution.columns, execution.allRows)

        val now = ZonedDateTime.now(clock)
        val meta = ReportRunMeta(
            environment = properties.environment,
            generatedAt = now.format(timestampFormatter),
            lastRunAt = now.format(timestampFormatter),
            rowCount = totalRows,
            elapsedMs = execution.elapsedMs,
            truncated = truncated
        )

        return ReportRunResponse(
            name = entity.name,
            meta = meta,
            query = execution.query,
            columns = execution.columns,
            rows = rows,
            summaries = summaries
        )
    }

    fun generatePdf(id: UUID, ctx: UpstreamContext, request: ReportRunRequest): ByteArray {
        val totalStart = System.nanoTime()
        val entity = repository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val jasperTemplate = entity.jasperTemplate ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Relatorio sem template Jasper vinculado"
        )

        val execution = executeReport(entity, ctx, request.params)
        val sanitizedJrxml = sanitizeJrxml(jasperTemplate.jrxml)
        val templateBytes = sanitizedJrxml.toByteArray(StandardCharsets.UTF_8)

        try {
            configureJasperCompiler()
            val compileStart = System.nanoTime()
            val jasperReport = ByteArrayInputStream(templateBytes).use { input ->
                JasperCompileManager.compileReport(input)
            }
            val compileMs = (System.nanoTime() - compileStart) / 1_000_000

            val coerceRowsStart = System.nanoTime()
            val dataRows = if (request.safe) {
                coerceRowsForTemplate(execution.allRows, jasperReport.mainDataset.fields)
            } else {
                execution.allRows
            }
            val coerceRowsMs = (System.nanoTime() - coerceRowsStart) / 1_000_000

            val dataSource = JRMapCollectionDataSource(dataRows)
            val coerceParamsStart = System.nanoTime()
            val logoBytes = loadLogoBytes()
            val jasperParams = mutableMapOf<String, Any?>(
                "REPORT_NAME" to entity.name,
                "REPORT_QUERY" to execution.query
            ).apply {
                if (request.safe) {
                    putAll(coerceParamsForTemplate(request.params, jasperReport.parameters))
                } else {
                    putAll(request.params)
                }
                if (logoBytes != null) {
                    put("LOGO_STREAM", ByteArrayInputStream(logoBytes))
                    put("LOGO_URL", this@ReportService::class.java.classLoader.getResource(LOGO_RESOURCE_PATH)?.toExternalForm())
                }
            }
            val coerceParamsMs = (System.nanoTime() - coerceParamsStart) / 1_000_000

            val fillStart = System.nanoTime()
            val jasperPrint = JasperFillManager.fillReport(jasperReport, jasperParams, dataSource)
            val fillMs = (System.nanoTime() - fillStart) / 1_000_000

            val exportStart = System.nanoTime()
            val pdfBytes = JasperExportManager.exportReportToPdf(jasperPrint)
            val exportMs = (System.nanoTime() - exportStart) / 1_000_000
            val totalMs = (System.nanoTime() - totalStart) / 1_000_000

            log.info(
                "PDF benchmark reportId={} templateId={} safe={} rows={} cols={} sqlMs={} compileMs={} coerceRowsMs={} coerceParamsMs={} fillMs={} exportMs={} totalMs={}",
                id,
                jasperTemplate.id,
                request.safe,
                execution.allRows.size,
                execution.columns.size,
                execution.elapsedMs,
                compileMs,
                coerceRowsMs,
                coerceParamsMs,
                fillMs,
                exportMs,
                totalMs
            )
            return pdfBytes
        } catch (ex: JRException) {
            log.error(
                "Falha ao gerar PDF Jasper reportId={} templateId={} templateName={} error={}",
                id,
                jasperTemplate.id,
                jasperTemplate.name,
                ex.message,
                ex
            )
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Falha ao gerar PDF Jasper: ${ex.message}",
                ex
            )
        } catch (ex: IllegalArgumentException) {
            log.warn(
                "Validacao falhou na geracao de PDF reportId={} templateId={} motivo={}",
                id,
                jasperTemplate.id,
                ex.message
            )
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "Erro de validacao", ex)
        }
    }

    fun validate(request: ReportValidationRequest, ctx: UpstreamContext): ReportValidationResponse {
        val errors = mutableListOf<String>()
        val queryTemplate = request.sql.trim()
        val variables = try {
            normalizeVariables(request.variables)
        } catch (ex: IllegalArgumentException) {
            errors += ex.message ?: "Configuracao de variaveis invalida"
            emptyList()
        }

        if (queryTemplate.isBlank()) {
            errors += "SQL nao pode ser vazia"
            return ReportValidationResponse(valid = false, errors = errors, renderedQuery = null)
        }

        if (request.enforceReadOnly) {
            validateReadOnlyQuery(queryTemplate)?.let { errors += it }
        }

        val placeholdersInQuery = extractPlaceholders(queryTemplate)
        val variableKeys = variables.map { it.key }.toSet()
        val unknownPlaceholders = placeholdersInQuery - variableKeys
        if (unknownPlaceholders.isNotEmpty()) {
            errors += "Placeholders sem variavel configurada: ${unknownPlaceholders.joinToString(", ")}"
        }

        variables.forEach { variable ->
            if (!placeholdersInQuery.contains(variable.key)) return@forEach
            if (!variable.multiple) return@forEach

            val hasWrongInSyntax = Regex("\\bin\\s*\\(\\s*:${Regex.escape(variable.key)}\\s*\\)", RegexOption.IGNORE_CASE)
                .containsMatchIn(queryTemplate)
            if (hasWrongInSyntax) {
                errors += "Variavel multipla '${variable.key}' deve usar 'IN :${variable.key}' (sem parenteses)"
                return@forEach
            }

            val hasExpectedInSyntax = Regex("\\bin\\s*:${Regex.escape(variable.key)}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(queryTemplate)
            if (!hasExpectedInSyntax) {
                errors += "Variavel multipla '${variable.key}' deve ser usada com IN :${variable.key}"
            }
        }

        val renderedQuery = runCatching {
            buildQueryWithParams(queryTemplate, variables, request.params, request.enforceRequired)
        }.getOrElse { ex ->
            errors += ex.message ?: "Falha ao montar SQL"
            null
        }

        if (errors.isNotEmpty() || renderedQuery == null) {
            return ReportValidationResponse(valid = false, errors = errors, renderedQuery = renderedQuery)
        }

        if (request.validateSyntax) {
            runCatching {
                sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, "EXPLAIN $renderedQuery", asDict = true, withDescription = true)
            }.onFailure { ex ->
                errors += "Falha de sintaxe/execucao no banco: ${ex.message ?: "erro desconhecido"}"
            }
        }

        return ReportValidationResponse(valid = errors.isEmpty(), errors = errors, renderedQuery = renderedQuery)
    }

    fun listVariableOptions(
        reportId: UUID,
        variableKey: String,
        ctx: UpstreamContext,
        request: ReportVariableOptionsRequest
    ): List<ReportVariableOptionResponse> {
        val entity = repository.findById(reportId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Report nao encontrado")
        }
        accessControl.requireReportAccess(entity, AccessAction.RUN)
        val variable = entity.variables.firstOrNull { it.key.equals(variableKey, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Variavel '$variableKey' nao encontrada")
        return try {
            val optionsSql = normalizeOptionsSql(variable.optionsSql)
            val query = buildQueryWithParams(optionsSql, entity.variables, request.params, enforceRequired = false)
            val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
            val rows = extractOptionRows(result)

            if (rows.isEmpty()) {
                extractOptionColumnsFromDescription(result)?.let { validateOptionColumns(it) }
                return emptyList()
            }

            val columns = validateOptionColumns(rows.first().keys)
            val limit = (request.limit ?: defaultFilterOptionsLimit).coerceIn(1, properties.reports.maxRows)

            rows.take(limit).map { row ->
                ReportVariableOptionResponse(
                    valor = row[columns.valor],
                    descricao = row[columns.descricao]?.toString() ?: ""
                )
            }
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message ?: "Erro de validacao", ex)
        }
    }

    private fun computeSummaries(columns: List<String>, rows: List<Map<String, Any?>>): List<ReportSummary> =
        columns.mapNotNull { column ->
            var sum = 0.0
            var hasValue = false
            var valid = true
            for (row in rows) {
                val value = row[column] ?: continue
                when (value) {
                    is Number -> {
                        sum += value.toDouble()
                        hasValue = true
                    }
                    else -> {
                        valid = false
                        break
                    }
                }
            }
            if (valid && hasValue) ReportSummary(column = column, sum = sum) else null
        }

    private fun ReportEntity.toResponse(): ReportResponse {
        val created = createdAt ?: error("createdAt ausente")
        val updated = updatedAt ?: error("updatedAt ausente")
        return ReportResponse(
            id = id?.toString() ?: error("id ausente"),
            name = name,
            templateName = templateName,
            sql = sql,
            description = description,
            archived = archived,
            folder = folder?.let { reportFolder ->
                ReportFolderSummaryResponse(
                    id = reportFolder.id?.toString() ?: error("id da pasta ausente"),
                    name = reportFolder.name,
                    archived = reportFolder.archived
                )
            },
            jasperTemplate = jasperTemplate?.let { template ->
                JasperTemplateSummaryResponse(
                    id = template.id?.toString() ?: error("id do template Jasper ausente"),
                    name = template.name,
                    archived = template.archived
                )
            },
            variables = variables
                .sortedBy { it.orderIndex }
                .map { variable ->
                    ReportVariableResponse(
                        id = variable.id?.toString() ?: error("id da variavel ausente"),
                        key = variable.key,
                        label = variable.label,
                        type = variable.type,
                        required = variable.required,
                        multiple = variable.multiple,
                        defaultValue = variable.defaultValue,
                        optionsSql = variable.optionsSql,
                        orderIndex = variable.orderIndex
                    )
                },
            createdAt = created.toEpochMilli(),
            updatedAt = updated.toEpochMilli()
        )
    }

    private fun resolveFolder(folderId: UUID?): ReportFolderEntity? {
        if (folderId == null) return null
        return folderRepository.findById(folderId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Pasta de relatorio nao encontrada")
        }
    }

    private fun resolveJasperTemplate(templateId: UUID?): ReportJasperTemplateEntity? {
        if (templateId == null) return null
        return jasperTemplateRepository.findById(templateId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Template Jasper nao encontrado")
        }
    }

    private fun executeReport(
        entity: ReportEntity,
        ctx: UpstreamContext,
        params: Map<String, Any?>
    ): ExecutionResult {
        val queryTemplate = entity.sql.trim()
        require(queryTemplate.isNotBlank()) { "SQL nao pode ser vazia" }
        ReadOnlySqlValidator.requireReadOnly(queryTemplate)
        val query = buildQueryWithParams(queryTemplate, entity.variables, params)

        val start = System.nanoTime()
        val result = sqlExecClient.exec(ctx.endpointUrl, ctx.bearer, query, true, true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val tabular = EmailReportFormatter.toTabular(result)
        val columns = tabular?.columns ?: emptyList()
        val allRows = tabular?.rows?.map { row ->
            columns.zip(row).associate { (column, value) -> column to value }
        } ?: emptyList()

        return ExecutionResult(
            query = query,
            columns = columns,
            allRows = allRows,
            elapsedMs = elapsedMs
        )
    }

    private fun buildQueryWithParams(
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

    private fun extractPlaceholders(query: String): Set<String> =
        placeholderRegex.findAll(query).map { it.groupValues[1] }.toSet()

    private fun validateReadOnlyQuery(queryTemplate: String): String? {
        return ReadOnlySqlValidator.validate(queryTemplate)
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

    private fun normalizeOptionsSql(optionsSql: String?): String {
        val query = optionsSql?.trim().orEmpty()
        if (query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Variavel sem SQL de opcoes configurado")
        }

        val withoutTrailingSemicolon = query.removeSuffix(";").trim()
        require(withoutTrailingSemicolon.isNotBlank()) { "SQL de opcoes nao pode ser vazio" }
        require(!withoutTrailingSemicolon.contains(";")) { "SQL de opcoes deve conter apenas uma consulta" }
        val startsLikeSelect = withoutTrailingSemicolon.startsWith("select", ignoreCase = true) ||
            withoutTrailingSemicolon.startsWith("with", ignoreCase = true)
        require(startsLikeSelect) { "SQL de opcoes deve comecar com SELECT ou WITH" }
        require(
            !writeKeywordsRegex.containsMatchIn(withoutTrailingSemicolon)
        ) { "SQL de opcoes deve ser somente leitura" }

        return withoutTrailingSemicolon
    }

    private fun extractOptionRows(result: Map<String, Any?>): List<Map<String, Any?>> {
        val data = result["data"] as? List<*> ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        return data.mapIndexed { index, raw ->
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException(
                "Linha ${index + 1} do SQL de opcoes nao esta no formato objeto"
            )
            map.entries
                .filter { it.key is String }
                .associate { (key, value) -> key as String to value }
        }
    }

    private fun extractOptionColumnsFromDescription(result: Map<String, Any?>): Set<String>? {
        val description = result["description"] as? List<*> ?: return null
        val keys = description.mapNotNull { raw ->
            val column = raw as? Map<*, *> ?: return@mapNotNull null
            val name = column["name"] ?: column["column_name"] ?: column["columnName"] ?: column["field"]
            name?.toString()
        }.toSet()
        return keys.takeIf { it.isNotEmpty() }
    }

    private fun validateOptionColumns(columns: Collection<String>): OptionColumns {
        require(columns.size == 2) {
            "SQL de opcoes deve retornar exatamente 2 colunas: valor e descricao"
        }

        val valorKey = columns.firstOrNull { it.equals(optionColumnValor, ignoreCase = true) }
        val descricaoKey = columns.firstOrNull { it.equals(optionColumnDescricao, ignoreCase = true) }
        require(valorKey != null && descricaoKey != null) {
            "SQL de opcoes deve retornar as colunas 'valor' e 'descricao'"
        }

        return OptionColumns(valor = valorKey, descricao = descricaoKey)
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

    private fun sanitizeJrxml(raw: String): String {
        var result = raw

        // Remove uuid only from jasperReport root tag, where some generated variants are incompatible.
        result = result.replace(
            Regex("""(<\s*jasperReport\b[^>]*?)\s+uuid\s*=\s*"[^"]*"([^>]*>)""", RegexOption.IGNORE_CASE)
        ) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}"
        }

        // Jasper 7 expects <query>, so normalize any legacy <queryString> to <query language="sql">.
        result = result.replace(
            Regex("""<\s*queryString\b[^>]*>(.*?)<\s*/\s*queryString\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { match ->
            "<query language=\"sql\">${match.groupValues[1]}</query>"
        }

        // Reports are filled from JRMapCollectionDataSource; disable json query executer usage.
        result = result.replace(
            Regex("""<\s*query\b[^>]*language\s*=\s*"json"[^>]*>.*?<\s*/\s*query\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) {
            "<query language=\"sql\"><![CDATA[]]></query>"
        }

        return result
    }

    private fun configureJasperCompiler() {
        val context = DefaultJasperReportsContext.getInstance()
        val properties = JRPropertiesUtil.getInstance(context)
        properties.setProperty("net.sf.jasperreports.compiler.class", JASPER_JDT_COMPILER)
        properties.setProperty("net.sf.jasperreports.compiler.java", JASPER_JDT_COMPILER)
        properties.setProperty("net.sf.jasperreports.default.font.name", "DejaVu Sans")
        properties.setProperty("net.sf.jasperreports.default.pdf.font.name", "DejaVu Sans")
        properties.setProperty("net.sf.jasperreports.default.pdf.encoding", "Identity-H")
        properties.setProperty("net.sf.jasperreports.default.pdf.embedded", "true")
    }

    private fun loadLogoBytes(): ByteArray? {
        val stream = this::class.java.classLoader.getResourceAsStream(LOGO_RESOURCE_PATH)
        if (stream == null) {
            log.warn("Logo fixa nao encontrada em classpath: {}", LOGO_RESOURCE_PATH)
            return null
        }
        return stream.use { it.readBytes() }
    }

    private fun coerceRowsForTemplate(
        rows: List<Map<String, Any?>>,
        fields: Array<JRField>
    ): List<Map<String, Any?>> {
        val expectedTypesByField = fields.associate { it.name to it.valueClassName }
        return rows.map { row ->
            row.mapValues { (key, value) ->
                coerceForExpectedType(value, expectedTypesByField[key], "field '$key'")
            }
        }
    }

    private fun coerceParamsForTemplate(
        params: Map<String, Any?>,
        reportParameters: Array<JRParameter>
    ): Map<String, Any?> {
        val expectedTypesByParam = reportParameters
            .filterNot { it.isSystemDefined }
            .associate { it.name to it.valueClassName }
        return params.mapValues { (key, value) ->
            coerceForExpectedType(value, expectedTypesByParam[key], "param '$key'")
        }
    }

    private fun coerceForExpectedType(value: Any?, expectedClassName: String?, label: String): Any? {
        if (value == null || expectedClassName.isNullOrBlank()) return value

        return runCatching {
            when (expectedClassName) {
                "java.lang.String" -> value.toString()
                "java.lang.Long", "long" -> toBigDecimal(value).toLong()
                "java.lang.Integer", "int" -> toBigDecimal(value).toInt()
                "java.lang.Short", "short" -> toBigDecimal(value).toShort()
                "java.lang.Byte", "byte" -> toBigDecimal(value).toByte()
                "java.lang.Double", "double" -> toBigDecimal(value).toDouble()
                "java.lang.Float", "float" -> toBigDecimal(value).toFloat()
                "java.math.BigDecimal" -> toBigDecimal(value)
                "java.math.BigInteger" -> toBigInteger(value)
                "java.lang.Boolean", "boolean" -> toBooleanValue(value)
                "java.util.Date" -> toDateValue(value)
                else -> value
            }
        }.getOrElse { ex ->
            log.warn(
                "Safe mode: nao foi possivel converter {} para {} (valor='{}'). Usando null. motivo={}",
                label,
                expectedClassName,
                value,
                ex.message
            )
            null
        }
    }

    private fun toBigDecimal(value: Any): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is BigInteger -> BigDecimal(value)
            is Number -> BigDecimal(value.toString())
            is String -> value.trim().let { BigDecimal(it) }
            else -> throw IllegalArgumentException("valor nao numerico")
        }

    private fun toBigInteger(value: Any): BigInteger =
        when (value) {
            is BigInteger -> value
            is BigDecimal -> value.toBigInteger()
            is Number -> BigInteger(value.toString())
            is String -> value.trim().let { BigInteger(it) }
            else -> throw IllegalArgumentException("valor nao inteiro")
        }

    private fun toBooleanValue(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "y", "sim", "s" -> true
                "false", "0", "no", "n", "nao", "não" -> false
                else -> throw IllegalArgumentException("valor boolean invalido")
            }
            else -> throw IllegalArgumentException("valor boolean invalido")
        }

    private fun toDateValue(value: Any): Date =
        when (value) {
            is Date -> value
            is LocalDate -> Date.from(value.atStartOfDay(ZoneId.systemDefault()).toInstant())
            is LocalDateTime -> Date.from(value.atZone(ZoneId.systemDefault()).toInstant())
            is OffsetDateTime -> Date.from(value.toInstant())
            is ZonedDateTime -> Date.from(value.toInstant())
            is String -> {
                val text = value.trim()
                runCatching { Date.from(OffsetDateTime.parse(text).toInstant()) }.getOrNull()
                    ?: runCatching { Date.from(ZonedDateTime.parse(text).toInstant()) }.getOrNull()
                    ?: runCatching {
                        Date.from(LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant())
                    }.getOrNull()
                    ?: runCatching {
                        Date.from(LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant())
                    }.getOrNull()
                    ?: throw IllegalArgumentException("valor date invalido")
            }
            else -> throw IllegalArgumentException("valor date invalido")
        }

    private data class ExecutionResult(
        val query: String,
        val columns: List<String>,
        val allRows: List<Map<String, Any?>>,
        val elapsedMs: Long
    )

    private fun normalizeVariables(input: List<ReportVariableRequest>): List<ReportVariableEntity> {
        val seenKeys = mutableSetOf<String>()
        return input.mapIndexed { index, variable ->
            val key = variable.key.trim()
            val label = variable.label.trim()
            val type = variable.type.trim().lowercase()
            require(type in allowedVariableTypes) {
                "Tipo de variavel invalido: '$type'. Use string, number, date, datetime ou boolean"
            }
            require(seenKeys.add(key.lowercase())) { "Variavel duplicada: '$key'" }

            ReportVariableEntity(
                key = key,
                label = label,
                type = type,
                required = variable.required,
                multiple = variable.multiple,
                defaultValue = variable.defaultValue?.trim().takeUnless { it.isNullOrBlank() },
                optionsSql = variable.optionsSql?.trim().takeUnless { it.isNullOrBlank() },
                orderIndex = variable.orderIndex ?: index
            )
        }
    }

    private data class OptionColumns(
        val valor: String,
        val descricao: String
    )
}
