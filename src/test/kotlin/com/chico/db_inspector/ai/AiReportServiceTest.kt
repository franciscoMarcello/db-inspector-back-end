package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.report.ReportComparisonService
import com.chico.dbinspector.report.ReportEntity
import com.chico.dbinspector.report.ReportFolderService
import com.chico.dbinspector.report.ReportJasperTemplateRepository
import com.chico.dbinspector.report.ReportJasperTemplateService
import com.chico.dbinspector.report.ReportPdfService
import com.chico.dbinspector.report.ReportQueryService
import com.chico.dbinspector.report.ReportService
import com.chico.dbinspector.report.ReportVariableService
import com.chico.dbinspector.report.ReportVersionContent
import com.chico.dbinspector.report.ReportVersionResponse
import com.chico.dbinspector.report.ReportVersionService
import com.chico.dbinspector.service.HanaQueryService
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Duration

class AiReportServiceTest {

    private val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
    private val versionService = Mockito.mock(ReportVersionService::class.java)
    private val hana = Mockito.mock(HanaQueryService::class.java)
    private val auditService = Mockito.mock(com.chico.dbinspector.auth.AdminAuditService::class.java)
    private val properties = DbInspectorProperties()
    private val guard = AiSqlGuard(sqlExecClient, properties, jacksonObjectMapper())
    private val service = AiReportService(
        reportService = Mockito.mock(ReportService::class.java),
        versionService = versionService,
        folderService = Mockito.mock(ReportFolderService::class.java),
        templateService = Mockito.mock(ReportJasperTemplateService::class.java),
        templateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java),
        variableService = ReportVariableService(),
        queryService = ReportQueryService(),
        pdfService = Mockito.mock(ReportPdfService::class.java),
        guard = guard,
        jrxmlGuard = AiJrxmlGuard(),
        limiter = AiQueryLimiter(properties),
        approvals = Mockito.mock(AiApprovalService::class.java),
        sqlExecClient = sqlExecClient,
        properties = properties,
        hana = hana,
        comparisonService = ReportComparisonService(),
        auditService = auditService
    )
    private val ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer x")
    private val executed = mutableListOf<String>()
    private var columns = listOf("fazenda", "kg")
    private var planSchema = "agricola"
    private var dataRows: List<Map<String, Any?>> = emptyList()

    @BeforeEach
    fun setup() {
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            val query = inv.getArgument<String>(2)
            executed += query
            when {
                query.startsWith("EXPLAIN") -> mapOf(
                    "data" to listOf(mapOf("QUERY PLAN" to listOf(mapOf("Plan" to mapOf("Total Cost" to 10.0, "Schema" to planSchema, "Relation Name" to "colheita")))))
                )
                query.contains("ai_opcoes") -> mapOf("data" to emptyList<Any>(), "fields" to listOf(mapOf("name" to "valor"), mapOf("name" to "descricao")))
                query.contains("ai_comparacao") -> mapOf("data" to dataRows, "fields" to columns.map { mapOf("name" to it) })
                else -> mapOf("data" to emptyList<Any>(), "fields" to columns.map { mapOf("name" to it) })
            }
        }
    }

    private fun definition(
        sql: String = "SELECT f.nome AS fazenda, sum(c.peso_real) AS kg FROM agricola.colheita c JOIN agro.fazenda f ON f.id = c.fazenda_id WHERE c.dt::date >= :data_inicio AND (:fazenda IS NULL OR c.fazenda_id IN :fazenda) GROUP BY f.nome",
        variables: List<AiReportVariable> = listOf(
            AiReportVariable("data_inicio", "Data inicial", "date"),
            AiReportVariable("fazenda", "Fazenda", "string", obrigatoria = false, multipla = true, sqlOpcoes = "SELECT f.id AS valor, f.nome AS descricao FROM agro.fazenda f")
        ),
        jrxml: String? = null
    ) = AiReportDefinition(nome = "Colheita por fazenda", sql = sql, variaveis = variables, jrxml = jrxml)

    @Test
    fun `definicao correta valida e devolve as colunas`() {
        val result = service.validate(AiReportValidationRequest(definition(), mapOf("data_inicio" to "2025-03-01")), ctx)

        assertTrue(result.valido, result.problemas.toString())
        assertEquals(listOf("fazenda", "kg"), result.colunas)
        assertTrue(executed.any { it.startsWith("EXPLAIN") && it.contains("'2025-03-01'") })
        assertTrue(executed.any { it.contains("ai_opcoes") })
    }

    @Test
    fun `problemas estaticos voltam todos juntos e nao vao ao banco`() {
        val bad = definition(
            sql = "SELECT * FROM agricola.colheita c WHERE c.id IN (:fazenda) AND c.x = :sem_variavel -- comentario",
            variables = listOf(AiReportVariable("fazenda", "Fazenda", "string", obrigatoria = false, multipla = true))
        )

        val result = service.validate(AiReportValidationRequest(bad), ctx)

        assertFalse(result.valido)
        val rules = result.problemas.map { it.regra }.toSet()
        assertTrue("comentario_proibido" in rules)
        assertTrue("placeholder_sem_variavel" in rules)
        assertTrue("variavel_multipla" in rules)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `tabela fora da lista e acusada pelo plano`() {
        planSchema = "public"

        val result = service.validate(AiReportValidationRequest(definition()), ctx)

        assertTrue(result.problemas.any { it.regra == "schema_nao_permitido" })
    }

    @Test
    fun `campo do JRXML sem coluna no SQL e acusado`() {
        val jrxml = """
            <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                <field name="fazenda" class="java.lang.String"/>
                <field name="romaneio" class="java.lang.String"/>
                <detail><band height="20"><element kind="textField" x="0" y="0" width="200" height="20"><expression><![CDATA[${'$'}F{fazenda}]]></expression></element></band></detail>
            </jasperReport>
        """.trimIndent()

        val result = service.validate(AiReportValidationRequest(definition(jrxml = jrxml)), ctx)

        val problem = result.problemas.single { it.regra == "campo_sem_coluna" }
        assertTrue(problem.mensagem.contains("romaneio"))
    }

    @Test
    fun `JRXML perigoso e recusado sem ir ao banco`() {
        val jrxml = """<jasperReport name="t" language="java"><detail><band height="20"><element kind="textField"><expression><![CDATA[Runtime.getRuntime().exec("id").toString()]]></expression></element></band></detail></jasperReport>"""

        val result = service.validate(AiReportValidationRequest(definition(jrxml = jrxml)), ctx)

        assertTrue(result.problemas.any { it.regra == "expressao_nao_permitida" })
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `variavel declarada e nao usada vira aviso, nao erro`() {
        val variables = definition().variaveis + AiReportVariable("safra", "Safra", "string", obrigatoria = false)

        val result = service.validate(AiReportValidationRequest(definition(variables = variables)), ctx)

        assertTrue(result.valido)
        assertTrue(result.avisos.any { it.regra == "variavel_nao_usada" })
    }

    @Test
    fun `previa exige REPORT_RUN tambem fora do REST`() {
        val principal = com.chico.dbinspector.auth.AuthUserPrincipal(java.util.UUID.randomUUID(), "IA: t", "a@b.c", "", true, listOf("REPORT_WRITE"))
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        try {
            val ex = assertThrows<AiRuleException> { service.preview(java.util.UUID.randomUUID(), 1, AiPreviewRequest(), ctx) }
            assertEquals("proibido", ex.regra)
            Mockito.verifyNoInteractions(versionService)
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `criar rascunho com problema nao grava nada`() {
        assertThrows<AiValidationException> {
            service.createDraft(AiReportDraftRequest(definition(sql = "SELECT nextval('s')", variables = emptyList())), ctx)
        }

        Mockito.verifyNoInteractions(versionService)
    }

    @Test
    fun `criar rascunho valido grava pela versao e repassa a nota`() {
        Mockito.`when`(versionService.createDraftReport(anyContent(), Mockito.eq("conferido em marco/2025") ?: "")).thenReturn(
            ReportVersionResponse(
                reportId = "r1", versionNumber = 1, status = "DRAFT", baseVersionNumber = null, name = "x", description = null,
                folderId = null, sql = "", secondSql = null, comparisonKey = null, variables = emptyList(), jasperTemplateId = null,
                jrxml = null, authorType = "AI_TOKEN", authorName = "IA: t", note = "conferido em marco/2025", createdAt = 0,
                updatedAt = null, publishedAt = null, publishedBy = null
            )
        )

        val response = service.createDraft(AiReportDraftRequest(definition(), "conferido em marco/2025"), ctx)

        assertEquals("r1", response.relatorioId)
        assertEquals(1, response.versao)
        assertTrue(response.mensagem.contains("Publicar e decisao de uma pessoa"))
    }

    private fun anyContent(): ReportVersionContent {
        Mockito.any(ReportVersionContent::class.java)
        return ReportVersionContent("", null, null, "", null, null, emptyList(), null, null)
    }

    private fun anyString(): String {
        Mockito.anyString()
        return ""
    }

    private fun anyDuration(): Duration {
        Mockito.any(Duration::class.java)
        return Duration.ZERO
    }

    private fun asAiToken(vararg authorities: String) {
        val principal = com.chico.dbinspector.auth.AuthUserPrincipal(java.util.UUID.randomUUID(), "IA: t", "ana@example.com", "", true, authorities.toList())
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.authorities).apply {
                details = AiTokenAuthenticationDetails(java.util.UUID.randomUUID(), "t")
            }
    }

    private fun comparisonReport(id: java.util.UUID, sqlSap: String?) {
        Mockito.`when`(versionService.transientReport(id, 2)).thenReturn(
            ReportEntity(
                id = id,
                name = "Saidas x OIGE",
                sql = "SELECT c.id AS doc, c.peso_real AS kg FROM agricola.colheita c",
                secondSql = sqlSap,
                comparisonKey = "doc"
            )
        )
    }

    @Test
    fun `previa de comparacao confere os dois lados e avisa alias que so difere em maiusculas`() {
        asAiToken("REPORT_RUN")
        try {
            val id = java.util.UUID.randomUUID()
            comparisonReport(id, "SELECT \"U_Id_Agromobi\" AS \"doc\", \"Quantity\" AS KG FROM IGE1")
            columns = listOf("doc", "kg")
            dataRows = listOf(mapOf("doc" to "1", "kg" to 10), mapOf("doc" to "2", "kg" to 20))
            Mockito.`when`(hana.execLimited(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(
                HanaQueryService.QueryResult(listOf("doc", "KG"), listOf(mapOf("doc" to "1", "KG" to 10), mapOf("doc" to "3", "KG" to 30)))
            )

            val result = service.compare(id, 2, AiPreviewRequest(), ctx)

            assertEquals(1, result.iguais)
            assertEquals(1, result.apenasAgromobi)
            assertEquals(1, result.apenasSap)
            assertEquals(emptyList<String>(), result.colunasComparadas)
            assertEquals(listOf("kg"), result.colunasSoNoAgromobi)
            assertTrue(result.avisos.any { it.contains("MAIUSCULO") }, result.avisos.toString())
            assertTrue(executed.any { it.contains("ai_comparacao LIMIT") })
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `previa de comparacao sem token de IA nao chega ao SAP`() {
        val principal = com.chico.dbinspector.auth.AuthUserPrincipal(java.util.UUID.randomUUID(), "Ana", "ana@example.com", "", true, listOf("REPORT_RUN"))
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        try {
            val ex = assertThrows<AiRuleException> { service.compare(java.util.UUID.randomUUID(), 2, AiPreviewRequest(), ctx) }

            assertEquals("so_token_ia", ex.regra)
            Mockito.verifyNoInteractions(hana)
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `sqlSap com escrita escondida reprova a definicao`() {
        val bad = definition().copy(sqlSap = "SELECT * FROM OIGE FOR SHARE LOCK")

        val result = service.validate(AiReportValidationRequest(bad, mapOf("data_inicio" to "2025-03-01")), ctx)

        assertTrue(result.problemas.any { it.caminho == "sqlSap" && it.regra == "sap_somente_leitura" }, result.problemas.toString())
    }


    @Test
    fun `previa de comparacao recusa sqlSap com estrela antes de consultar o AgroMobi e audita`() {
        asAiToken("REPORT_RUN")
        try {
            val id = java.util.UUID.randomUUID()
            comparisonReport(id, "SELECT * FROM OUSR")

            assertThrows<AiValidationException> { service.compare(id, 2, AiPreviewRequest(), ctx) }

            assertTrue(executed.isEmpty(), executed.toString())
            Mockito.verifyNoInteractions(hana)
            Mockito.verify(auditService).log(
                Mockito.eq("AI_COMPARE_SAP") ?: "", Mockito.eq("REPORT") ?: "", Mockito.eq(id.toString()) ?: "",
                Mockito.argThat<Map<String, Any?>> { it?.get("resultado") == "recusada" } ?: emptyMap()
            )
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

}
