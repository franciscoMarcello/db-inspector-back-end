package com.chico.dbinspector.report

import com.chico.dbinspector.config.DbInspectorProperties
import com.chico.dbinspector.service.SqlExecClient
import com.chico.dbinspector.web.UpstreamContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException

class ReportServiceQueryTemplateTest {

    @Test
    fun `buildQueryWithParams should replace placeholders with SQL literals`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(key = "name", label = "Name", type = "string", required = true, orderIndex = 0),
            ReportVariableEntity(key = "active", label = "Active", type = "boolean", required = true, orderIndex = 1),
            ReportVariableEntity(key = "amount", label = "Amount", type = "number", required = true, orderIndex = 2),
            ReportVariableEntity(key = "day", label = "Day", type = "date", required = true, orderIndex = 3)
        )

        val rendered = invokeBuildQueryWithParams(
            service = service,
            queryTemplate = "SELECT * FROM t WHERE name = :name AND active = :active AND amount = :amount AND day = :day",
            variables = variables,
            params = mapOf(
                "name" to "O'Reilly",
                "active" to "true",
                "amount" to "10.50",
                "day" to "2026-02-17"
            ),
            enforceRequired = true
        )

        assertEquals(
            "SELECT * FROM t WHERE name = 'O''Reilly' AND active = true AND amount = 10.50 AND day = '2026-02-17'",
            rendered
        )
    }

    @Test
    fun `buildQueryWithParams should fail for unknown params`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(key = "name", label = "Name", type = "string", required = true, orderIndex = 0)
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            invokeBuildQueryWithParams(
                service = service,
                queryTemplate = "SELECT * FROM t WHERE name = :name",
                variables = variables,
                params = mapOf("unknown" to "x"),
                enforceRequired = true
            )
        }

        assertEquals("Parametros desconhecidos: unknown", ex.message)
    }

    @Test
    fun `buildQueryWithParams should fail when required param is missing`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(key = "name", label = "Name", type = "string", required = true, orderIndex = 0)
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            invokeBuildQueryWithParams(
                service = service,
                queryTemplate = "SELECT * FROM t WHERE name = :name",
                variables = variables,
                params = emptyMap(),
                enforceRequired = true
            )
        }

        assertEquals("Parametro obrigatorio ausente: 'name'", ex.message)
    }

    @Test
    fun `buildQueryWithParams should use default value when param is omitted`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(
                key = "status",
                label = "Status",
                type = "string",
                required = false,
                defaultValue = "OPEN",
                orderIndex = 0
            )
        )

        val rendered = invokeBuildQueryWithParams(
            service = service,
            queryTemplate = "SELECT * FROM t WHERE status = :status",
            variables = variables,
            params = emptyMap(),
            enforceRequired = true
        )

        assertEquals("SELECT * FROM t WHERE status = 'OPEN'", rendered)
    }

    @Test
    fun `buildQueryWithParams should render IN clause for multiple variable`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(
                key = "status",
                label = "Status",
                type = "string",
                required = true,
                multiple = true,
                orderIndex = 0
            )
        )

        val rendered = invokeBuildQueryWithParams(
            service = service,
            queryTemplate = "SELECT * FROM t WHERE status IN :status",
            variables = variables,
            params = mapOf("status" to listOf("OPEN", "PENDING", "CLOSED")),
            enforceRequired = true
        )

        assertEquals("SELECT * FROM t WHERE status IN ('OPEN', 'PENDING', 'CLOSED')", rendered)
    }

    @Test
    fun `buildQueryWithParams should fail for empty list in multiple variable`() {
        val service = createService()
        val variables = listOf(
            ReportVariableEntity(
                key = "ids",
                label = "Ids",
                type = "number",
                required = true,
                multiple = true,
                orderIndex = 0
            )
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            invokeBuildQueryWithParams(
                service = service,
                queryTemplate = "SELECT * FROM t WHERE id IN :ids",
                variables = variables,
                params = mapOf("ids" to emptyList<Int>()),
                enforceRequired = true
            )
        }

        assertEquals("Parametro 'ids' nao pode ser lista vazia", ex.message)
    }

    @Test
    fun `validate should reject multiple variable when using IN with parentheses`() {
        val service = createService()

        val response = service.validate(
            request = ReportValidationRequest(
                sql = "SELECT chip, nome FROM confinamento.animal WHERE nome IN (:nome)",
                variables = listOf(
                    ReportVariableRequest(
                        key = "nome",
                        label = "Nome",
                        type = "string",
                        required = true,
                        multiple = true
                    )
                ),
                params = mapOf("nome" to listOf("FB.73", "FB.249")),
                validateSyntax = false
            ),
            ctx = UpstreamContext(endpointUrl = "http://localhost/sql/exec/", bearer = "Bearer test")
        )

        assertFalse(response.valid)
        assertTrue(response.errors.any { it.contains("deve usar 'IN :nome'") })
    }

    @Test
    fun `validate should accept multiple variable when using IN without parentheses`() {
        val service = createService()

        val response = service.validate(
            request = ReportValidationRequest(
                sql = "SELECT chip, nome FROM confinamento.animal WHERE nome IN :nome",
                variables = listOf(
                    ReportVariableRequest(
                        key = "nome",
                        label = "Nome",
                        type = "string",
                        required = true,
                        multiple = true
                    )
                ),
                params = mapOf("nome" to listOf("FB.73", "FB.249")),
                validateSyntax = false
            ),
            ctx = UpstreamContext(endpointUrl = "http://localhost/sql/exec/", bearer = "Bearer test")
        )

        assertTrue(response.valid)
        assertEquals(
            "SELECT chip, nome FROM confinamento.animal WHERE nome IN ('FB.73', 'FB.249')",
            response.renderedQuery
        )
    }

    private fun createService(): ReportService {
        val reportRepository = Mockito.mock(ReportRepository::class.java)
        val folderRepository = Mockito.mock(ReportFolderRepository::class.java)
        val jasperTemplateRepository = Mockito.mock(ReportJasperTemplateRepository::class.java)
        val accessControl = Mockito.mock(ReportAccessControlService::class.java)
        val sqlExecClient = SqlExecClient(
            WebClient.builder()
                .exchangeFunction(ExchangeFunction { Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()) })
                .build()
        )

        return ReportService(
            repository = reportRepository,
            folderRepository = folderRepository,
            jasperTemplateRepository = jasperTemplateRepository,
            accessControl = accessControl,
            sqlExecClient = sqlExecClient,
            properties = DbInspectorProperties()
        )
    }

    private fun invokeBuildQueryWithParams(
        service: ReportService,
        queryTemplate: String,
        variables: List<ReportVariableEntity>,
        params: Map<String, Any?>,
        enforceRequired: Boolean
    ): String {
        val method = ReportService::class.java.getDeclaredMethod(
            "buildQueryWithParams",
            String::class.java,
            List::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true

        try {
            return method.invoke(service, queryTemplate, variables, params, enforceRequired) as String
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
    }
}
