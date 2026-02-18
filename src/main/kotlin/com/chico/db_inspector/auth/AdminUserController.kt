package com.chico.dbinspector.auth

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController(
    private val service: AdminUserService
) {
    @GetMapping("/users")
    fun listUsers(): List<AdminUserResponse> = service.listUsers()

    @PostMapping("/users")
    fun createUser(@Valid @RequestBody request: AdminCreateUserRequest): AdminUserResponse =
        service.createUser(request)

    @PatchMapping("/users/{id}/active")
    fun setUserActive(
        @PathVariable id: UUID,
        @RequestBody request: AdminSetUserActiveRequest
    ): AdminUserResponse = service.setUserActive(id, request.active)

    @PostMapping("/users/{id}/roles")
    fun assignRole(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AdminAssignRoleRequest
    ): AdminUserResponse = service.assignRole(id, request.role)

    @DeleteMapping("/users/{id}/roles/{role}")
    fun removeRole(
        @PathVariable id: UUID,
        @PathVariable role: String
    ): AdminUserResponse = service.removeRole(id, role)

    @GetMapping("/roles")
    fun listRoles(): List<AdminRoleResponse> = service.listRoles()

    @GetMapping("/roles/{role}")
    fun getRole(@PathVariable role: String): AdminRoleResponse = service.getRole(role)

    @PostMapping("/roles")
    fun createRole(@Valid @RequestBody request: AdminCreateRoleRequest): AdminRoleResponse =
        service.createRole(request)

    @PutMapping("/roles/{role}")
    fun updateRole(
        @PathVariable role: String,
        @RequestBody request: AdminUpdateRoleRequest
    ): AdminRoleResponse = service.updateRole(role, request)

    @DeleteMapping("/roles/{role}")
    fun deleteRole(@PathVariable role: String): ResponseEntity<Void> {
        service.deleteRole(role)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/permissions")
    fun listPermissions(): List<String> = PermissionCodes.all

    @PostMapping("/users/{id}/revoke-refresh-tokens")
    fun revokeRefreshTokens(@PathVariable id: UUID): ResponseEntity<Void> {
        service.revokeRefreshTokens(id)
        return ResponseEntity.noContent().build()
    }
}
