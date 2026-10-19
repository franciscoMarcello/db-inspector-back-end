package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Duration

class AiSqlGuardTest {

    private val sqlExecClient = Mockito.mock(SqlExecClient::class.java)
    private val properties = DbInspectorProperties()
    private val guard = AiSqlGuard(sqlExecClient, properties, jacksonObjectMapper())
    private val ctx = UpstreamContext("https://api.example.com/sql/exec/", "Bearer x")

    private fun rules(sql: String) = guard.staticProblems(sql).map { it.regra }.toSet()

    @Test
    fun `deve aceitar consulta comum com CTE, literais e parametros`() {
        val sql = """
            WITH colheita AS (
                SELECT c.id, c.status, c.dt FROM agricola.colheita c
                WHERE c.status IN ('E', 'A') AND c.obs <> 'nextval(x) -- nao e comentario'
            )
            SELECT status, count(*) FROM colheita WHERE dt >= :dataInicio GROUP BY status
        """.trimIndent()

        assertEquals(emptySet<String>(), rules(sql))
    }

    @Test
    fun `deve bloquear DELETE dentro de CTE`() {
        assertTrue("somente_leitura" in rules("WITH d AS (DELETE FROM agro.fazenda RETURNING *) SELECT * FROM d"))
    }

    @Test
    fun `deve bloquear comentario de linha e de bloco`() {
        assertTrue("comentario_proibido" in rules("SELECT 1 -- oi"))
        assertTrue("comentario_proibido" in rules("SELECT /* oi */ 1"))
    }

    @Test
    fun `deve bloquear funcoes com efeito colateral`() {
        listOf(
            "SELECT nextval('agro.seq')",
            "SELECT setval('agro.seq', 1)",
            "SELECT set_config('statement_timeout', '0', false)",
            "SELECT pg_terminate_backend(123)",
            "SELECT pg_sleep(10)",
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT lo_import('/etc/passwd')",
            "SELECT * FROM dblink('host=x', 'select 1') AS t(a int)",
            "SELECT query_to_xml('select * from public.users', true, true, '')",
            "SELECT pg_advisory_lock(1)"
        ).forEach { sql ->
            assertTrue("funcao_proibida" in rules(sql), sql)
        }
    }

    @Test
    fun `nao deve deixar funcao proibida escapar por aspas, maiusculas, schema ou espacos`() {
        listOf(
            "SELECT \"nextval\"('agro.seq')",
            "SELECT NEXTVAL ('agro.seq')",
            "SELECT pg_catalog.nextval('agro.seq')",
            "SELECT \"pg_catalog\".\"nextval\"('agro.seq')",
            "SELECT nextval\n('agro.seq')"
        ).forEach { sql ->
            assertTrue("funcao_proibida" in rules(sql), sql)
        }
    }

    @Test
    fun `deve bloquear identificador por escape unicode`() {
        assertTrue("escape_unicode_proibido" in rules("SELECT U&\"\\006Eextval\"('agro.seq')"))
    }

    @Test
    fun `deve bloquear SELECT INTO e bloqueio de linha`() {
        assertTrue("select_into_proibido" in rules("SELECT * INTO copia FROM agro.fazenda"))
        assertTrue("bloqueio_de_linha_proibido" in rules("SELECT * FROM agro.fazenda FOR SHARE"))
    }

    @Test
    fun `deve bloquear mais de uma instrucao`() {
        assertTrue("somente_leitura" in rules("SELECT 1; DROP TABLE agro.fazenda"))
    }

    @Test
    fun `comentario e funcao dentro de texto nao contam`() {
        assertEquals(emptySet<String>(), rules("SELECT 'a -- b', E'it\\'s /* x */', \$\$nextval(1)\$\$ AS t"))
    }

    @Test
    fun `texto sem fechamento e recusado`() {
        assertTrue("sql_malformado" in rules("SELECT 'aberto"))
    }

    @Test
    fun `deve apontar a linha do problema`() {
        val problem = guard.staticProblems("SELECT 1\nFROM agro.x\nWHERE nextval('s') > 0").first { it.regra == "funcao_proibida" }
        assertEquals(3, problem.linha)
    }

    private fun stubDatabase(userFunctions: List<String>, failFunctions: Boolean = false) {
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            val query = inv.getArgument<String>(2)
            when {
                query.contains("pg_proc") && failFunctions -> throw IllegalStateException("HTTP 500, body={\"detail\":\"boom\"}")
                query.contains("pg_proc") -> mapOf("data" to userFunctions.map { mapOf("nome" to it) })
                else -> mapOf("data" to listOf(mapOf("QUERY PLAN" to planJson("agro", "fazenda", 10.0))))
            }
        }
    }

    @Test
    fun `funcao definida no proprio banco e recusada antes do EXPLAIN`() {
        stubDatabase(listOf("le_usuarios", "unaccent"))

        val ex = assertThrows<AiValidationException> { guard.check("SELECT agro.le_usuarios() AS x FROM agro.fazenda", ctx) }

        assertEquals("funcao_proibida", ex.problems.single().regra)
        assertTrue(ex.problems.single().mensagem.contains("le_usuarios"))
    }

    @Test
    fun `funcao do banco liberada na configuracao passa e funcao nativa nunca e barrada`() {
        stubDatabase(listOf("unaccent"))
        properties.ai.allowedFunctions = listOf("unaccent")

        val plan = guard.check("SELECT unaccent(f.nome), date_trunc('month', now()), count(*) FROM agro.fazenda f GROUP BY 1", ctx)

        assertEquals(setOf("agro.fazenda"), plan.relations)
    }

    @Test
    fun `sem conseguir ler as funcoes do banco a guarda recusa`() {
        stubDatabase(emptyList(), failFunctions = true)

        val ex = assertThrows<AiValidationException> { guard.check("SELECT 1 FROM agro.fazenda", ctx) }

        assertEquals("erro_no_banco", ex.problems.single().regra)
    }

    @Test
    fun `resposta das funcoes fora do formato tambem recusa, sem guardar lista vazia`() {
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            if (inv.getArgument<String>(2).contains("pg_proc")) mapOf("rows" to emptyList<Any>())
            else mapOf("data" to listOf(mapOf("QUERY PLAN" to planJson("agro", "fazenda", 10.0))))
        }

        val ex = assertThrows<AiValidationException> { guard.check("SELECT 1 FROM agro.fazenda", ctx) }
        assertEquals("erro_no_banco", ex.problems.single().regra)

        // Sem cache da resposta ruim: a proxima chamada consulta de novo.
        assertThrows<AiValidationException> { guard.check("SELECT 1 FROM agro.fazenda", ctx) }
        Mockito.verify(sqlExecClient, Mockito.times(2))
            .execWithTimeout(anyString(), anyString(), Mockito.contains("pg_proc") ?: "", anyDuration())
    }

    @Test
    fun `coluna sensivel citada no SQL e recusada`() {
        assertTrue("coluna_proibida" in rules("SELECT p.site_password FROM public.res_partner p"))
        assertTrue("coluna_proibida" in rules("SELECT c.nfe_certificado_senha FROM public.res_company c"))
        assertTrue("coluna_proibida" in rules("SELECT f.senha_web FROM br.funcionario f"))
        assertTrue("coluna_proibida" in rules("SELECT p.email, p.phone FROM public.res_partner p"))
        assertTrue("coluna_proibida" in rules("SELECT p.\"Site_Password\" FROM public.res_partner p"))
        assertTrue("coluna_proibida" in rules("SELECT p.minha_senha_x FROM agro.fazenda p"))
        assertEquals(emptySet<String>(), rules("SELECT p.id, p.nome FROM public.res_partner p"))
        assertEquals(emptySet<String>(), rules("SELECT 'site_password' AS texto"))
    }

    @Test
    fun `SELECT estrela em tabela com coluna sensivel e recusado pelo plano`() {
        stubPlan(
            listOf(
                mapOf(
                    "Plan" to mapOf(
                        "Total Cost" to 10.0, "Node Type" to "Seq Scan", "Schema" to "public", "Relation Name" to "res_partner",
                        "Output" to listOf("id", "nome", "email", "site_password")
                    )
                )
            )
        )

        val ex = assertThrows<AiValidationException> { guard.check("SELECT * FROM public.res_partner", ctx) }

        assertEquals(setOf("coluna_proibida"), ex.problems.map { it.regra }.toSet())
        assertTrue(ex.problems.any { it.mensagem.contains("site_password") })
    }

    @Test
    fun `linha inteira da tabela e recusada pelo plano`() {
        stubPlan(
            listOf(
                mapOf(
                    "Plan" to mapOf(
                        "Total Cost" to 10.0, "Schema" to "public", "Relation Name" to "res_partner",
                        // Formas reais do EXPLAIN VERBOSE do Postgres 16 para linha inteira.
                        "Output" to listOf("(to_jsonb(p.*))", "((row_to_json(p.*))::text)", "(p.*)::text")
                    )
                )
            )
        )

        val ex = assertThrows<AiValidationException> { guard.check("SELECT to_jsonb(p) FROM public.res_partner p", ctx) }

        assertEquals("linha_inteira_proibida", ex.problems.first().regra)
    }

    @Test
    fun `colunas permitidas da mesma tabela passam no plano`() {
        stubPlan(
            listOf(
                mapOf(
                    "Plan" to mapOf(
                        "Total Cost" to 10.0, "Schema" to "public", "Relation Name" to "res_partner",
                        "Output" to listOf("id", "nome", "(count(*))")
                    )
                )
            )
        )

        assertEquals(setOf("public.res_partner"), guard.check("SELECT id, nome FROM public.res_partner", ctx).relations)
    }

    @Test
    fun `plano em schema nao liberado e recusado`() {
        stubPlan(planJson(schema = "public", relation = "users", cost = 10.0))

        val ex = assertThrows<AiValidationException> { guard.check("SELECT * FROM public.users", ctx) }

        assertEquals("schema_nao_permitido", ex.problems.single().regra)
    }

    @Test
    fun `view ou subconsulta que le schema bloqueado tambem e recusada pelo plano`() {
        stubPlan(planJson(schema = "pg_catalog", relation = "pg_authid", cost = 10.0))

        val ex = assertThrows<AiValidationException> { guard.check("SELECT * FROM agro.v_inocente", ctx) }

        assertTrue(ex.problems.any { it.regra == "schema_nao_permitido" })
    }

    @Test
    fun `custo acima do limite e recusado, salvo com liberacao`() {
        stubPlan(planJson(schema = "agricola", relation = "colheita", cost = properties.ai.maxPlanCost * 2))

        val ex = assertThrows<AiValidationException> { guard.check("SELECT * FROM agricola.colheita", ctx) }
        assertEquals("custo_acima_do_limite", ex.problems.single().regra)

        val plan = guard.check("SELECT * FROM agricola.colheita", ctx, ignoreCost = true)
        assertEquals(setOf("agricola.colheita"), plan.relations)
    }

    @Test
    fun `plano devolvido como texto JSON tambem e lido`() {
        stubPlan(planJsonText("agro", "fazenda", 42.0))

        val plan = guard.check("SELECT * FROM agro.fazenda", ctx)

        assertEquals(42.0, plan.totalCost)
        assertEquals(setOf("agro"), plan.schemas)
    }

    @Test
    fun `erro do banco vira problema legivel sem cabecalhos HTTP`() {
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            if (inv.getArgument<String>(2).contains("pg_proc")) return@thenAnswer mapOf("data" to emptyList<Any>())
            throw IllegalStateException("HTTP 400, CT=application/json, Location=null, body={\"detail\":\"column x does not exist\"}")
        }

        val ex = assertThrows<AiValidationException> { guard.check("SELECT x FROM agro.fazenda", ctx) }

        assertEquals("erro_no_banco", ex.problems.single().regra)
        assertEquals("column x does not exist", ex.problems.single().mensagem)
    }

    private fun stubPlan(plan: Any) {
        Mockito.`when`(sqlExecClient.execWithTimeout(anyString(), anyString(), anyString(), anyDuration())).thenAnswer { inv ->
            if (inv.getArgument<String>(2).contains("pg_proc")) mapOf("data" to emptyList<Any>())
            else mapOf("data" to listOf(mapOf("QUERY PLAN" to plan)))
        }
    }

    private fun planJson(schema: String, relation: String, cost: Double): List<Map<String, Any?>> = listOf(
        mapOf(
            "Plan" to mapOf(
                "Node Type" to "Hash Join",
                "Total Cost" to cost,
                "Plans" to listOf(
                    mapOf("Node Type" to "Seq Scan", "Schema" to schema, "Relation Name" to relation, "Total Cost" to 1.0)
                )
            )
        )
    )

    private fun planJsonText(schema: String, relation: String, cost: Double) =
        jacksonObjectMapper().writeValueAsString(planJson(schema, relation, cost))

    private fun anyString(): String {
        Mockito.anyString()
        return ""
    }

    private fun anyDuration(): Duration {
        Mockito.any(Duration::class.java)
        return Duration.ZERO
    }
}
