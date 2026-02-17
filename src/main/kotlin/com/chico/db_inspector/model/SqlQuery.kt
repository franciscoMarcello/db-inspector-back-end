// src/main/kotlin/com/chico/db_inspector/model/SqlQuery.kt
package com.chico.db_inspector.model

data class SqlQuery(
    val query: String,
    val asDict: Boolean? = true,          // default útil p/ mapear colunas
    val withDescription: Boolean? = true, // inclui "fields"
    val page: Int? = 0,
    val size: Int? = 200
)
