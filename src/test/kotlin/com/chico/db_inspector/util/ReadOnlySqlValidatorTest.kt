package com.chico.dbinspector.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadOnlySqlValidatorTest {

    @Test
    fun `validate should accept select and with`() {
        assertNull(ReadOnlySqlValidator.validate("SELECT * FROM animal"))
        assertNull(ReadOnlySqlValidator.validate("WITH t AS (SELECT 1) SELECT * FROM t"))
    }

    @Test
    fun `validate should reject non read only query`() {
        assertEquals(
            "SQL deve comecar com SELECT ou WITH",
            ReadOnlySqlValidator.validate("DELETE FROM animal WHERE id = 1")
        )
    }

    @Test
    fun `validate should reject multiple statements`() {
        assertEquals(
            "SQL deve conter apenas uma consulta",
            ReadOnlySqlValidator.validate("SELECT 1; SELECT 2")
        )
    }
}
