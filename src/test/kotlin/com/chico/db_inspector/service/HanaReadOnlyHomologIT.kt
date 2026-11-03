package com.chico.dbinspector.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Contra o SAP HANA real, so em schema de homologacao. Roda quando HANA_IT_URL, HANA_IT_USER e HANA_IT_PASSWORD
 * estao no ambiente; HANA_IT_SCHEMAS lista os schemas (padrao HOMOLOG,HOMOLOG2). Producao nunca entra aqui.
 */
@EnabledIfEnvironmentVariable(named = "HANA_IT_URL", matches = ".+")
class HanaReadOnlyHomologIT {

    companion object {
        private val ataques = listOf(
            "INSERT INTO OIGE (\"DocEntry\") VALUES (-1)",
            "UPSERT OIGE (\"DocEntry\") VALUES (-1) WITH PRIMARY KEY",
            "UPDATE OIGE SET \"Comments\" = 'ia' WHERE 1 = 0",
            "DELETE FROM OIGE WHERE 1 = 0",
            "SELECT 1 FROM DUMMY; DELETE FROM OIGE WHERE 1 = 0",
            "WITH x AS (SELECT 1 AS a FROM DUMMY) INSERT INTO OIGE SELECT * FROM x",
            "SELECT * INTO OIGE_COPIA FROM OIGE",
            "SELECT TOP 1 * FROM OIGE FOR UPDATE",
            "SELECT TOP 1 * FROM OIGE FOR SHARE LOCK",
            "SELECT 1 FROM DUMMY -- ; DROP TABLE OIGE",
            "CREATE TABLE IA_TESTE (A INT)",
            "CALL SYS.TRUNCATE_PLAN_CACHE()",
            "DO BEGIN DELETE FROM OIGE WHERE 1 = 0; END",
            "EXPLAIN PLAN FOR SELECT * FROM OIGE"
        )

        @JvmStatic
        fun schemas(): List<String> =
            (System.getenv("HANA_IT_SCHEMAS") ?: "HOMOLOG,HOMOLOG2").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        @JvmStatic
        fun ataquesPorSchema() = schemas().flatMap { schema -> ataques.map { arrayOf(schema, it) } }

        fun service(schema: String): HanaQueryService {
            require(!schema.equals("SBOGRUPOROVEMA", ignoreCase = true)) { "Teste de integracao nao roda em producao" }
            val base = System.getenv("HANA_IT_URL").substringBefore('?')
            val dataSource = DriverManagerDataSource("$base?currentSchema=$schema", System.getenv("HANA_IT_USER"), System.getenv("HANA_IT_PASSWORD"))
            dataSource.setDriverClassName("com.sap.db.jdbc.Driver")
            return HanaQueryService(JdbcTemplate(dataSource))
        }

        private fun totalOige(service: HanaQueryService): String =
            service.execLimited("SELECT COUNT(*) AS N, MAX(\"UpdateDate\") AS U FROM OIGE", 1, 30).rows.single().toString()
    }

    @ParameterizedTest
    @MethodSource("schemas")
    fun `deve ler o B1 no schema de homologacao`(schema: String) {
        val service = service(schema)

        val atual = service.execLimited("SELECT CURRENT_SCHEMA AS S FROM DUMMY", 1, 30)
        val docs = service.execLimited("SELECT TOP 5 \"DocEntry\", \"DocNum\", \"DocDate\" FROM OIGE ORDER BY \"DocEntry\" DESC", 3, 30)

        assertEquals(schema, atual.rows.single()["S"])
        assertEquals(listOf("DocEntry", "DocNum", "DocDate"), docs.columns)
        assertTrue(docs.rows.size <= 3, "maxRows nao respeitado: ${docs.rows.size}")
    }

    @ParameterizedTest
    @MethodSource("ataquesPorSchema")
    fun `deve barrar escrita antes de chegar ao SAP e nada muda no schema`(schema: String, sql: String) {
        val service = service(schema)
        val antes = totalOige(service)

        assertThrows<IllegalArgumentException>(sql) { service.execLimited(sql, 10, 30) }

        assertEquals(antes, totalOige(service))
    }
}
