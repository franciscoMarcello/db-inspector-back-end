package com.chico.dbinspector.email

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class EmailTestRequest(
    @field:NotBlank
    @field:Size(max = 2048)
    val to: String,

    @field:Size(max = 2048)
    val cc: String? = null,

    @field:Size(max = 256)
    val subject: String? = null,

    @field:Size(max = 4096)
    val message: String? = null
)
