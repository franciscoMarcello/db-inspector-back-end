package com.chico.dbinspector.util

object UpstreamUtils {
    fun validateExternalUrl(
        url: String,
        allowLocalhost: Boolean = true,
        requirePathSuffix: String? = "/sql/exec/"
    ) {
        require(url.length <= 2048) { "URL muito longa" }
        val u = try { java.net.URI(url) } catch (e: Exception) {
            throw IllegalArgumentException("URL inválida: ${e.message}")
        }
        require(u.scheme == "http" || u.scheme == "https") { "URL deve ser http(s)" }
        val host = u.host ?: error("URL sem host")

        if (!allowLocalhost) {
            require(!(host.equals("localhost", true) || host == "127.0.0.1")) {
                "Host localhost bloqueado"
            }
        }

        if (!requirePathSuffix.isNullOrBlank()) {
            require(u.path.orEmpty().endsWith(requirePathSuffix)) {
                "Path deve terminar em $requirePathSuffix"
            }
        }
    }


    fun resolveBearer(authorization: String?, apiToken: String?): String {
        val auth = authorization?.trim().orEmpty()
        if (auth.isNotEmpty()) {
            require(auth.startsWith("Bearer ", ignoreCase = true)) { "Authorization deve usar Bearer" }
            val token = auth.removePrefix("Bearer ").trim()
            require(token.isNotEmpty()) { "Bearer token vazio" }
            return "Bearer $token"
        }
        val token = apiToken?.trim().orEmpty()
        require(token.isNotEmpty()) { "Token upstream ausente: X-Upstream-Authorization Bearer ou X-API-Token" }
        return "Bearer $token"
    }
}
