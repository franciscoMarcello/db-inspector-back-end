package com.chico.dbinspector.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        val body = mapOf("message" to (ex.message ?: "Requisicao invalida"))
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }
}
