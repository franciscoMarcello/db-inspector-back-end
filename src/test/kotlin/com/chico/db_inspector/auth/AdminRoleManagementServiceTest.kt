package com.chico.dbinspector.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class AdminRoleManagementServiceTest {

    private val roleRepository = Mockito.mock(RoleRepository::class.java)
    private val permissionRepository = Mockito.mock(PermissionRepository::class.java)
    private val userRoleRepository = Mockito.mock(UserRoleRepository::class.java)
    private val rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)
    private val keycloakAdmin = Mockito.mock(KeycloakAdminClient::class.java)
    private val service = AdminRoleManagementService(
        roleRepository, permissionRepository, userRoleRepository, rolePermissionRepository, auditService, keycloakAdmin
    )

    private val role = RoleEntity(id = UUID.randomUUID(), name = "ANALISTA")

    @Test
    fun `perfil novo vira role no Keycloak depois de gravado`() {
        Mockito.`when`(roleRepository.save(Mockito.any(RoleEntity::class.java))).thenReturn(role)
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))

        service.createRole(AdminCreateRoleRequest(name = "analista"))

        val order = Mockito.inOrder(roleRepository, auditService, keycloakAdmin)
        order.verify(roleRepository).save(Mockito.any(RoleEntity::class.java))
        order.verify(auditService).log("ADMIN_ROLE_CREATE", "ROLE", "ANALISTA", mapOf("permissions" to emptyList<String>()))
        order.verify(keycloakAdmin).ensureRole("ANALISTA")
    }

    @Test
    fun `commit que falha depois do Keycloak aceitar desfaz a role criada`() {
        Mockito.`when`(roleRepository.save(Mockito.any(RoleEntity::class.java))).thenReturn(role)
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(keycloakAdmin.ensureRole("ANALISTA")).thenReturn(RoleEnsureResult.CREATED)
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.createRole(AdminCreateRoleRequest(name = "ANALISTA"))
            Mockito.verify(keycloakAdmin, Mockito.never()).removeRole(Mockito.anyString())

            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        Mockito.verify(keycloakAdmin).removeRole("ANALISTA")
    }

    @Test
    fun `rollback por criacao paralela ja gravada nao apaga a role que ela usa`() {
        Mockito.`when`(roleRepository.save(Mockito.any(RoleEntity::class.java))).thenReturn(role)
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(keycloakAdmin.ensureRole("ANALISTA")).thenReturn(RoleEnsureResult.CREATED)
        // Antes de gravar: nome livre. Depois do rollback desta: a outra criacao ja gravou o mesmo nome.
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA"))
            .thenReturn(Optional.empty(), Optional.of(RoleEntity(id = UUID.randomUUID(), name = "ANALISTA")))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.createRole(AdminCreateRoleRequest(name = "ANALISTA"))

            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        Mockito.verify(keycloakAdmin, Mockito.never()).removeRole(Mockito.anyString())
    }

    @Test
    fun `resultado de commit desconhecido nao desfaz nada no Keycloak`() {
        Mockito.`when`(roleRepository.save(Mockito.any(RoleEntity::class.java))).thenReturn(role)
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(keycloakAdmin.ensureRole("ANALISTA")).thenReturn(RoleEnsureResult.CREATED)
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.createRole(AdminCreateRoleRequest(name = "ANALISTA"))

            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        Mockito.verify(keycloakAdmin, Mockito.never()).removeRole(Mockito.anyString())
    }

    @Test
    fun `commit que falha depois de renomear no Keycloak volta o nome antigo`() {
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.updateRole("ANALISTA", AdminUpdateRoleRequest(name = "ANALISTA_FISCAL"))

            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        val order = Mockito.inOrder(keycloakAdmin)
        order.verify(keycloakAdmin).renameRole("ANALISTA", "ANALISTA_FISCAL")
        order.verify(keycloakAdmin).renameRole("ANALISTA_FISCAL", "ANALISTA")
    }

    @Test
    fun `lista mostra a situacao de cada perfil no Keycloak com uma consulta so`() {
        val manual = RoleEntity(id = UUID.randomUUID(), name = "ADMIN")
        val missing = RoleEntity(id = UUID.randomUUID(), name = "GESTOR")
        Mockito.`when`(roleRepository.findAllByOrderByNameAsc()).thenReturn(listOf(manual, role, missing))
        Mockito.`when`(keycloakAdmin.listRoles()).thenReturn(mapOf("ADMIN" to false, "ANALISTA" to true))

        val statuses = service.listRoles().associate { it.name to it.keycloak }

        assertEquals(
            mapOf(
                "ADMIN" to KeycloakRoleStatus.MANUAL,
                "ANALISTA" to KeycloakRoleStatus.SYNCED,
                "GESTOR" to KeycloakRoleStatus.MISSING
            ),
            statuses
        )
        Mockito.verify(keycloakAdmin, Mockito.times(1)).listRoles()
    }

    @Test
    fun `Keycloak fora do ar nao impede a lista de abrir`() {
        Mockito.`when`(roleRepository.findAllByOrderByNameAsc()).thenReturn(listOf(role))
        Mockito.`when`(keycloakAdmin.listRoles()).thenThrow(KeycloakAdminException("Nao foi possivel falar com o Keycloak"))

        assertEquals(KeycloakRoleStatus.UNAVAILABLE, service.listRoles().single().keycloak)
    }

    @Test
    fun `sem sincronizacao a lista nao informa situacao no Keycloak`() {
        val withoutKeycloak = AdminRoleManagementService(
            roleRepository, permissionRepository, userRoleRepository, rolePermissionRepository, auditService
        )
        Mockito.`when`(roleRepository.findAllByOrderByNameAsc()).thenReturn(listOf(role))

        assertEquals(null, withoutKeycloak.listRoles().single().keycloak)
    }

    @Test
    fun `salvar perfil que falta no Keycloak cria a role`() {
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(permissionRepository.findByCode("REPORT_READ"))
            .thenReturn(Optional.of(PermissionEntity(id = UUID.randomUUID(), code = "REPORT_READ")))
        Mockito.`when`(keycloakAdmin.ensureExistingRole("ANALISTA")).thenReturn(RoleEnsureResult.CREATED)

        val saved = service.updateRole("ANALISTA", AdminUpdateRoleRequest(permissions = listOf("REPORT_READ")))

        assertEquals(KeycloakRoleStatus.SYNCED, saved.keycloak)
        Mockito.verify(keycloakAdmin).ensureExistingRole("ANALISTA")
    }

    @Test
    fun `Keycloak fora do ar nao impede salvar as permissoes do perfil`() {
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(permissionRepository.findByCode("REPORT_READ"))
            .thenReturn(Optional.of(PermissionEntity(id = UUID.randomUUID(), code = "REPORT_READ")))
        Mockito.`when`(keycloakAdmin.ensureExistingRole("ANALISTA"))
            .thenThrow(KeycloakAdminException("Nao foi possivel falar com o Keycloak", uncertain = true))

        val saved = service.updateRole("ANALISTA", AdminUpdateRoleRequest(permissions = listOf("REPORT_READ")))

        assertEquals(KeycloakRoleStatus.UNAVAILABLE, saved.keycloak)
        Mockito.verify(rolePermissionRepository).save(Mockito.any(RolePermissionEntity::class.java))
    }

    @Test
    fun `falha no Keycloak ao criar desfaz o perfil com erro legivel`() {
        Mockito.`when`(roleRepository.save(Mockito.any(RoleEntity::class.java))).thenReturn(role)
        Mockito.`when`(roleRepository.findById(role.id ?: error("sem id"))).thenReturn(Optional.of(role))
        Mockito.`when`(keycloakAdmin.ensureRole("ANALISTA")).thenThrow(KeycloakAdminException("Nao foi possivel falar com o Keycloak"))

        val error = assertThrows(ResponseStatusException::class.java) {
            service.createRole(AdminCreateRoleRequest(name = "ANALISTA"))
        }

        assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
        assertEquals("Perfil nao foi criado: Nao foi possivel falar com o Keycloak", error.reason)
    }

    @Test
    fun `nome que nao serve de role e recusado antes de gravar`() {
        val error = assertThrows(ResponseStatusException::class.java) {
            service.createRole(AdminCreateRoleRequest(name = "gestor compras"))
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        Mockito.verify(roleRepository, Mockito.never()).save(Mockito.any(RoleEntity::class.java))
        Mockito.verifyNoInteractions(keycloakAdmin)
    }

    @Test
    fun `renomear perfil renomeia a role no Keycloak`() {
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))

        service.updateRole("ANALISTA", AdminUpdateRoleRequest(name = "analista_fiscal"))

        assertEquals("ANALISTA_FISCAL", role.name)
        Mockito.verify(keycloakAdmin).renameRole("ANALISTA", "ANALISTA_FISCAL")
    }

    @Test
    fun `nao exclui perfil que alguem ainda tem no Keycloak`() {
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        Mockito.`when`(keycloakAdmin.roleInUse("ANALISTA")).thenReturn(true)

        val error = assertThrows(ResponseStatusException::class.java) { service.deleteRole("ANALISTA") }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        Mockito.verify(roleRepository, Mockito.never()).delete(Mockito.any(RoleEntity::class.java))
        Mockito.verify(keycloakAdmin, Mockito.never()).removeRole(Mockito.anyString())
    }

    @Test
    fun `nao exclui perfil de usuario que ainda entra com senha local`() {
        val localUser = AppUserEntity(id = UUID.randomUUID(), email = "local@test.com", active = true)
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        Mockito.`when`(userRoleRepository.findAllByRoleId(role.id ?: error("sem id")))
            .thenReturn(listOf(UserRoleEntity(id = UUID.randomUUID(), user = localUser, role = role)))

        val error = assertThrows(ResponseStatusException::class.java) { service.deleteRole("ANALISTA") }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        Mockito.verifyNoInteractions(keycloakAdmin)
    }

    @Test
    fun `excluir perfil limpa a copia local e exclui a role por ultimo`() {
        val ssoUser = AppUserEntity(id = UUID.randomUUID(), email = "ana@test.com", keycloakId = "kc-ana-0001", active = true)
        val staleLink = UserRoleEntity(id = UUID.randomUUID(), user = ssoUser, role = role)
        Mockito.`when`(roleRepository.findByNameIgnoreCase("ANALISTA")).thenReturn(Optional.of(role))
        Mockito.`when`(userRoleRepository.findAllByRoleId(role.id ?: error("sem id"))).thenReturn(listOf(staleLink))

        service.deleteRole("ANALISTA")

        val order = Mockito.inOrder(userRoleRepository, roleRepository, keycloakAdmin)
        order.verify(keycloakAdmin).roleInUse("ANALISTA")
        order.verify(userRoleRepository).delete(staleLink)
        order.verify(roleRepository).delete(role)
        order.verify(keycloakAdmin).removeRole("ANALISTA")
    }
}
