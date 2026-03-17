package com.chico.dbinspector.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class PasswordResetRequestDto(
    @field:Email
    @field:NotBlank
    val email: String
)

data class PasswordResetConfirmDto(
    @field:NotBlank
    val token: String,
    @field:NotBlank
    val newPassword: String
)

data class PasswordResetMessageResponse(
    val message: String
)
