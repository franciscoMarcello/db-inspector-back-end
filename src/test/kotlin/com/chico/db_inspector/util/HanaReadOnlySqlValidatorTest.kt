package com.chico.dbinspector.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HanaReadOnlySqlValidatorTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SELECT TOP 5 \"DocNum\", \"DocDate\" FROM OIGE ORDER BY \"DocEntry\" DESC",
            "WITH t AS (SELECT \"DocEntry\" FROM OIGE) SELECT COUNT(*) FROM t",
            "select \"UpdateDate\", \"CreateDate\", \"DocTotal\" from oige where \"DocDate\" >= '2026-01-01'",
            "SELECT REPLACE('a-b', '-', '') AS r FROM DUMMY",
            "SELECT \"DocEntry\" FROM OIGE WITH HINT (NO_CS_JOIN)",
            "SELECT COUNT(*) AS n, COUNT( * ) AS m FROM OIGE",
            "SELECT \"Quantity\" * \"Price\" AS total, \"Quantity\"*2 AS dobro FROM IGE1",
            "SELECT TOP 10 \"USER_CODE\", \"U_NAME\" FROM OUSR",
            "SELECT COLUMN_NAME FROM SYS.TABLE_COLUMNS WHERE TABLE_NAME = 'OUSR'",
            "SELECT 1 FROM DUMMY;",
            "SELECT 1 FROM DUMMY\nUNION ALL\nSELECT 2 FROM DUMMY"
        ]
    )
    fun `deve aceitar leitura comum do B1`(sql: String) {
        assertNull(HanaReadOnlySqlValidator.validate(sql))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // comando direto
            "INSERT INTO OIGE (\"DocEntry\") VALUES (1)",
            "UPSERT OIGE (\"DocEntry\") VALUES (1) WITH PRIMARY KEY",
            "UPDATE OIGE SET \"Comments\" = 'x'",
            "DELETE FROM OIGE",
            "MERGE INTO OIGE USING DUMMY ON 1 = 1 WHEN MATCHED THEN DELETE",
            "TRUNCATE TABLE OIGE",
            "DROP TABLE OIGE",
            "CREATE TABLE LEITURA.T (A INT)",
            "ALTER TABLE OIGE ADD (X INT)",
            "CALL SYS.TRUNCATE_PLAN_CACHE()",
            "DO BEGIN DELETE FROM OIGE; END",
            "SET SCHEMA B1ADMIN",
            "EXPLAIN PLAN FOR SELECT * FROM OIGE",
            "GRANT SELECT ON OIGE TO PUBLIC",
            "IMPORT FROM '/tmp/x'",
            // segunda consulta grudada
            "SELECT 1 FROM DUMMY; DELETE FROM OIGE",
            "sElEcT 1 FROM DUMMY WHERE 1 = 1; TrUnCaTe TABLE OIGE",
            "SELECT 1 FROM DUMMY; COMMIT",
            // escrita escondida dentro de SELECT/WITH
            "WITH x AS (SELECT 1 AS a FROM DUMMY) INSERT INTO B1ADMIN.T SELECT * FROM x",
            "WITH a AS (DELETE FROM OIGE RETURNING *) SELECT * FROM a",
            "SELECT * INTO B1ADMIN.COPIA FROM OIGE",
            "SELECT * FROM OIGE FOR UPDATE",
            "SELECT * FROM OIGE FOR\nUPDATE NOWAIT",
            "SELECT * FROM OIGE FOR SHARE LOCK",
            "SELECT B1ADMIN.SEQ.NEXTVAL FROM DUMMY",
            "SELECT * FROM (CALL B1ADMIN.PROC())",
            "SELECT * FROM OIGE AS OF COMMIT ID 1",
            // comentario escondendo trecho
            "SELECT 1 FROM DUMMY -- ; DROP TABLE OIGE",
            "SELECT 1 /* x */ FROM DUMMY",
            "SELECT 1 FROM DUMMY */",
            // nao comeca com SELECT/WITH
            "(SELECT 1 FROM DUMMY)",
            "  ",
            "LOAD OIGE ALL"
        ]
    )
    fun `deve bloquear escrita e comando no SAP`(sql: String) {
        assertNotNull(HanaReadOnlySqlValidator.validate(sql), sql)
    }

    @Test
    fun `deve bloquear palavra proibida mesmo como identificador entre aspas`() {
        assertNotNull(HanaReadOnlySqlValidator.validate("SELECT \"INSERT\" FROM DUMMY"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // coluna sensivel citada, com ou sem apelido
            "SELECT \"PASSWORD\" FROM OUSR",
            "SELECT \"USER_CODE\", \"PASSWORD4\" AS x FROM OUSR",
            "SELECT \"LastPwds\" FROM AUSR",
            "SELECT \"SenhaCertificado\" FROM \"IV_IB_Certificado\"",
            "SELECT \"ApiCloudFioriSecret\" FROM \"IV_IB_CompanyLocal\"",
            "SELECT \"AcessoTokenApiWhatsApp\" FROM \"IV_IB_CompanyLocal\"",
            "SELECT \"AuthPwd\" FROM ADM1",
            "SELECT \"U_SenhaEmail\" FROM \"@AMFS_PRMT\"",
            "SELECT LENGTH(\"password\") FROM OUSR",
            "SELECT \"USER_CODE\" FROM OUSR WHERE \"PASSWORD\" LIKE 'a%'",
            // * que traria a coluna sem citar o nome
            "SELECT * FROM OUSR",
            "SELECT TOP 5 * FROM OUSR",
            "SELECT DISTINCT *FROM OUSR",
            "SELECT T0.* FROM OUSR T0",
            "SELECT \"T0\" . * FROM OUSR \"T0\"",
            "SELECT \"USER_CODE\", * FROM OUSR",
            "SELECT 'a' AS c1 FROM DUMMY WHERE 1 = 0 UNION ALL SELECT * FROM OUSR",
            "SELECT * FROM OUSR FOR JSON",
            "SELECT \"USER_CODE\" FROM (SELECT * FROM OUSR)",
            "SELECT\n*\nFROM OUSR"
        ]
    )
    fun `deve esconder senha, chave e token do SAP`(sql: String) {
        assertNotNull(HanaReadOnlySqlValidator.validate(sql), sql)
    }

    @Test
    fun `deve reconhecer coluna sensivel pelo nome`() {
        listOf("PASSWORD", "Password", "LastPwds", "U_SenhaEmail", "ChaveSecretaApiWhatsApp", "ApiCloudFioriToken").forEach {
            assertTrue(HanaReadOnlySqlValidator.isSensitiveColumn(it), it)
        }
        listOf("USER_CODE", "DocNum", "U_Id_Agromobi", "CardCode").forEach {
            assertFalse(HanaReadOnlySqlValidator.isSensitiveColumn(it), it)
        }
    }

}
