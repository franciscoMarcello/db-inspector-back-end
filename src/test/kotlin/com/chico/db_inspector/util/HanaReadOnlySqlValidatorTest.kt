package com.chico.dbinspector.util

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
            "SELECT * FROM OIGE WITH HINT (NO_CS_JOIN)",
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
}
