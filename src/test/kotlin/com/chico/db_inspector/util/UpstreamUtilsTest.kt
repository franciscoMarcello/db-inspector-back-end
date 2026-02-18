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

    @Test
    fun `resolveBearer should prefer authorization header`() {
        val bearer = UpstreamUtils.resolveBearer(
            authorization = "Bearer token-abc",
            apiToken = "fallback-token"
        )

        assertEquals("Bearer token-abc", bearer)
    }

    @Test
    fun `resolveBearer should fallback to api token`() {
        val bearer = UpstreamUtils.resolveBearer(
            authorization = null,
            apiToken = "api-123"
        )

        assertEquals("Bearer api-123", bearer)
    }

    @Test
    fun `resolveBearer should fail when token is missing`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            UpstreamUtils.resolveBearer(authorization = null, apiToken = null)
        }

        assertEquals("Token upstream ausente: X-Upstream-Authorization Bearer ou X-API-Token", ex.message)
    }
}
