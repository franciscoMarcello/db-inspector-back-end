package com.chico.dbinspector.report

import jakarta.validation.constraints.NotBlank

data class ResourceAclRequest(
    @field:NotBlank
    val subjectType: String,
    @field:NotBlank
    val subject: String,
    val canView: Boolean = false,
    val canRun: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false
)

data class ResourceAclResponse(
    val id: String,
    val subjectType: String,
    val subject: String,
    val canView: Boolean,
    val canRun: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean
)
