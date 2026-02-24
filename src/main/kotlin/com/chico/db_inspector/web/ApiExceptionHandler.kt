package com.chico.dbinspector.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        val body = mapOf("message" to (ex.message ?: "Requisicao invalida"))
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(
        ex: ResponseStatusException,
        request: HttpServletRequest
    ): ResponseEntity<ApiErrorResponse> {
        val status = ex.statusCode
        val body = ApiErrorResponse(
            timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
            status = status.value(),
            error = status.reasonPhrase(),
            message = ex.reason ?: status.reasonPhrase(),
            path = request.requestURI
        )
        return ResponseEntity.status(status).body(body)
    }

    private fun HttpStatusCode.reasonPhrase(): String =
        HttpStatus.resolve(value())?.reasonPhrase ?: "Error"

    data class ApiErrorResponse(
        val timestamp: String,
        val status: Int,
        val error: String,
        val message: String,
        val path: String
    )
}
