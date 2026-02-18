// src/main/kotlin/com/chico/dbinspector/web/CorsMvcConfig.kt
package com.chico.dbinspector.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsMvcConfig(
    @Value("\${CORS_EXTRA_ORIGIN:}") private val corsExtraOrigin: String
) : WebMvcConfigurer {
    override fun addCorsMappings(reg: CorsRegistry) {
        val allowedOrigins = listOf(
            "http://localhost:4200",
            "http://127.0.0.1:4200",
            "http://localhost:8080",
            "http://localhost:8081"
        ) + listOfNotNull(corsExtraOrigin.takeIf { it.isNotBlank() })

        reg.addMapping("/api/db/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
            .allowedHeaders("X-SQL-EXEC-URL","X-API-Token","X-Upstream-Authorization","Authorization","Content-Type","Accept","X-Requested-With")
            .exposedHeaders("Location")
            .allowCredentials(true)
            .maxAge(3600)

        reg.addMapping("/api/auth/**")
            .allowedOrigins(*allowedOrigins.toTypedArray())
            .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
            .allowedHeaders("Authorization","Content-Type","Accept","X-Requested-With")
            .exposedHeaders("Location")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
