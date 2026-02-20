package com.chico.dbinspector.auth

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

object PasswordPolicy {
    private const val MIN_LENGTH = 10

    private val upperRegex = Regex("[A-Z]")
    private val lowerRegex = Regex("[a-z]")
    private val digitRegex = Regex("[0-9]")
    private val specialRegex = Regex("[^A-Za-z0-9]")
    private val whitespaceRegex = Regex("\\s")

    fun validateOrThrow(rawPassword: String) {
        val password = rawPassword.trim()
        if (!isValid(password)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, validationMessage())
        }
    }

    fun isValid(password: String): Boolean =
        password.length >= MIN_LENGTH &&
            upperRegex.containsMatchIn(password) &&
            lowerRegex.containsMatchIn(password) &&
            digitRegex.containsMatchIn(password) &&
            specialRegex.containsMatchIn(password) &&
            !whitespaceRegex.containsMatchIn(password)

    fun validationMessage(): String =
        "Senha deve ter no minimo 10 caracteres, incluindo letra maiuscula, letra minuscula, numero e caractere especial, sem espacos"
}
