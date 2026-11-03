package com.chico.dbinspector.report

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Formatacao pt-BR para templates Jasper, passada a todo PDF como parametro FMT:
 *
 *   <parameter name="FMT" class="com.chico.dbinspector.report.JasperFormat"/>
 *   $P{FMT}.moeda($F{valor_total})
 *
 * Substitui o `new java.text.DecimalFormat(...)` repetido em cada campo. Nulo vira texto vazio.
 */
object JasperFormat {
    private val symbols = DecimalFormatSymbols(Locale.forLanguageTag("pt-BR"))
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private const val MAX_CASAS = 10
    private const val MAX_DIGITS = 400

    @JvmStatic
    @JvmOverloads
    fun numero(value: Any?, casas: Int = 2): String {
        val number = toBigDecimal(value) ?: return ""
        // Teto de casas: o template e escrito por IA e "casas" vira tamanho de texto.
        val digits = casas.coerceIn(0, MAX_CASAS)
        val pattern = if (digits == 0) "#,##0" else "#,##0." + "0".repeat(digits)
        return DecimalFormat(pattern, symbols).format(number.setScale(digits, RoundingMode.HALF_UP))
    }

    @JvmStatic
    fun inteiro(value: Any?): String = numero(value, 0)

    @JvmStatic
    fun moeda(value: Any?): String = numero(value, 2).takeIf { it.isNotEmpty() }?.let { "R$ $it" } ?: ""

    /** Recebe o valor ja em pontos percentuais: 12.5 vira "12,50%". */
    @JvmStatic
    @JvmOverloads
    fun percentual(value: Any?, casas: Int = 2): String =
        numero(value, casas).takeIf { it.isNotEmpty() }?.let { "$it%" } ?: ""

    @JvmStatic
    fun data(value: Any?): String = toDateTime(value)?.format(dateFormatter) ?: ""

    @JvmStatic
    fun dataHora(value: Any?): String = toDateTime(value)?.format(dateTimeFormatter) ?: ""

    @JvmStatic
    fun texto(value: Any?): String = value?.toString()?.trim() ?: ""

    private fun toBigDecimal(value: Any?): BigDecimal? = when (value) {
        null -> null
        is BigDecimal -> value
        is Number -> runCatching { BigDecimal(value.toString()) }.getOrNull()
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        else -> null
    }?.takeIf { it.precision() - it.scale() <= MAX_DIGITS }

    private fun toDateTime(value: Any?): LocalDateTime? = when (value) {
        null -> null
        is LocalDateTime -> value
        is LocalDate -> value.atStartOfDay()
        is java.sql.Date -> value.toLocalDate().atStartOfDay()
        is Date -> value.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
        is String -> value.trim().let { text ->
            runCatching { LocalDateTime.parse(text.replace(' ', 'T').take(19)) }.getOrNull()
                ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay() }.getOrNull()
        }
        else -> null
    }
}
