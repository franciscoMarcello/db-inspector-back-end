// src/main/kotlin/com/chico/dbinspector/web/CorsMvcConfig.kt
package com.chico.dbinspector.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsMvcConfig : WebMvcConfigurer {
    override fun addCorsMappings(reg: CorsRegistry) {
        reg.addMapping("/api/db/**")
            .allowedOrigins("http://localhost:4200", "http://127.0.0.1:4200","http://localhost:8080","http://localhost:8081","http://172.18.10.76:4080")
            .allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
            .allowedHeaders("X-SQL-EXEC-URL","X-API-Token","Authorization","Content-Type","Accept","X-Requested-With")
            .exposedHeaders("Location")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
