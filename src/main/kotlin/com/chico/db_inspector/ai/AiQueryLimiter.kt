package com.chico.dbinspector.ai

import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Freio de carga no banco de producao por token: consultas por minuto e consultas simultaneas.
 * Em memoria porque o backend roda em instancia unica; pessoas (sem token) nao passam por aqui.
 */
@Component
class AiQueryLimiter(
    private val properties: DbInspectorProperties,
    private val clock: Clock = Clock.systemUTC()
) {
    private val windows = ConcurrentHashMap<UUID, ArrayDeque<Instant>>()
    private val running = ConcurrentHashMap<UUID, AtomicInteger>()

    fun <T> run(tokenId: UUID?, block: () -> T): T {
        if (tokenId == null) return block()
        registerCall(tokenId)
        val counter = running.computeIfAbsent(tokenId) { AtomicInteger(0) }
        if (counter.incrementAndGet() > properties.ai.maxConcurrentQueries) {
            counter.decrementAndGet()
            throw AiRuleException(
                HttpStatus.TOO_MANY_REQUESTS,
                "consulta_em_andamento",
                "Ja existe consulta deste token em andamento",
                "Espere a consulta anterior terminar antes de enviar outra."
            )
        }
        try {
            return block()
        } finally {
            counter.decrementAndGet()
        }
    }

    private fun registerCall(tokenId: UUID) {
        val now = Instant.now(clock)
        val window = windows.computeIfAbsent(tokenId) { ArrayDeque() }
        synchronized(window) {
            val limit = now.minus(Duration.ofMinutes(1))
            while (window.isNotEmpty() && window.first().isBefore(limit)) window.removeFirst()
            if (window.size >= properties.ai.queriesPerMinute) {
                throw AiRuleException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "limite_de_frequencia",
                    "Limite de ${properties.ai.queriesPerMinute} consultas por minuto atingido",
                    "Espere um minuto, ou junte hipoteses na mesma consulta (GROUP BY, CASE)."
                )
            }
            window.addLast(now)
        }
    }
}
