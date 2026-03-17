package com.chico.dbinspector.auth

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/password-reset")
class PasswordResetController(
    private val passwordResetService: PasswordResetService
) {
    @PostMapping("/request")
    fun request(@Valid @RequestBody request: PasswordResetRequestDto): PasswordResetMessageResponse {
        passwordResetService.requestReset(request.email)
        return PasswordResetMessageResponse("Se o email informado estiver cadastrado, voce receberá um link de redefinicao de senha.")
    }

    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody request: PasswordResetConfirmDto): PasswordResetMessageResponse {
        passwordResetService.confirmReset(request.token, request.newPassword)
        return PasswordResetMessageResponse("Senha redefinida com sucesso.")
    }
}
