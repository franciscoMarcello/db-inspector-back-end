package com.chico.dbinspector.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UpstreamUtilsTest {

    @Test
    fun `validateExternalUrl should accept valid https endpoint`() {
        UpstreamUtils.validateExternalUrl(
            url = "https://api.example.com/sql/exec/",
            allowLocalhost = false,
            requirePathSuffix = "/sql/exec/"
        )
    }

    @Test
    fun `validateExternalUrl should reject localhost when blocked`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            UpstreamUtils.validateExternalUrl(
                url = "http://localhost:8080/sql/exec/",
                allowLocalhost = false,
                requirePathSuffix = "/sql/exec/"
            )
        }

        assertEquals("Host localhost bloqueado", ex.message)
    }

    @Test
    fun `validateExternalUrl should reject invalid path suffix`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            UpstreamUtils.validateExternalUrl(
                url = "https://api.example.com/other",
                allowLocalhost = true,
                requirePathSuffix = "/sql/exec/"
            )
        }

        assertEquals("Path deve terminar em /sql/exec/", ex.message)
    }

}
