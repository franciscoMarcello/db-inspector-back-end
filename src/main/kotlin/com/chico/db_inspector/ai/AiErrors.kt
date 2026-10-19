package com.chico.dbinspector.ai

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Um problema encontrado na entrada da IA. Legivel por maquina de proposito: `regra` e um codigo
 * estavel, `caminho` diz onde, `linha` aponta a posicao no SQL ou no JRXML. A IA corrige sozinha.
 */
data class AiProblem(
    val caminho: String,
    val regra: String,
    val mensagem: String,
    val linha: Int? = null
)

class AiValidationException(val problems: List<AiProblem>) :
    RuntimeException(problems.joinToString("; ") { "${it.regra}: ${it.mensagem}" })

/** Regra de uso (limite, liberacao, permissao) com a dica do que a IA pode fazer a seguir. */
class AiRuleException(
    val status: HttpStatus,
    val regra: String,
    override val message: String,
    val dica: String? = null
) : RuntimeException(message)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["com.chico.dbinspector.ai"])
class AiExceptionHandler {

    @ExceptionHandler(AiValidationException::class)
    fun handleValidation(ex: AiValidationException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf("erro" to "validacao_falhou", "problemas" to ex.problems)
        )

    @ExceptionHandler(AiRuleException::class)
    fun handleRule(ex: AiRuleException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(ex.status).body(
            mapOf("erro" to ex.regra, "mensagem" to ex.message, "dica" to ex.dica)
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.badRequest().body(
            mapOf("erro" to "requisicao_invalida", "mensagem" to (ex.message ?: "Requisicao invalida"))
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBeanValidation(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "erro" to "requisicao_invalida",
                "problemas" to ex.bindingResult.fieldErrors.map {
                    AiProblem(caminho = it.field, regra = "campo_invalido", mensagem = it.defaultMessage ?: "invalido")
                }
            )
        )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleStatus(ex: ResponseStatusException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(ex.statusCode).body(
            mapOf("erro" to "erro_${ex.statusCode.value()}", "mensagem" to (ex.reason ?: "Erro"))
        )
}
