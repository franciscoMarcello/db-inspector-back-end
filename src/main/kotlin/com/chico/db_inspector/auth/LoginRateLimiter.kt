package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

@Component
class LoginRateLimiter(
    private val properties: DbInspectorProperties
) {
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun isAllowed(key: String): Boolean {
        val now = Instant.now()
        val windowSeconds = properties.security.loginWindowSeconds.toLong().coerceAtLeast(1L)
        val maxAttempts = properties.security.loginMaxAttempts.coerceAtLeast(1)
        val windowStart = now.minusSeconds(windowSeconds)

        val deque = attempts.computeIfAbsent(key) { ArrayDeque() }
        synchronized(deque) {
            while (deque.isNotEmpty() && deque.first().isBefore(windowStart)) {
                deque.removeFirst()
            }
            return deque.size < maxAttempts
        }
    }

    fun recordFailure(key: String) {
        val deque = attempts.computeIfAbsent(key) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(Instant.now())
        }
    }

    fun clear(key: String) {
        attempts.remove(key)
    }
}
