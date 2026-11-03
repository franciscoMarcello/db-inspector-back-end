package com.chico.dbinspector.auth

import com.chico.dbinspector.config.DbInspectorProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.util.Optional
import java.util.UUID

class KeycloakUserServiceTest {

    private val properties = DbInspectorProperties().apply { keycloak.clientId = "agroreports" }
    private val userRepository = Mockito.mock(AppUserRepository::class.java)
    private val roleRepository = Mockito.mock(RoleRepository::class.java)
    private val userRoleRepository = Mockito.mock(UserRoleRepository::class.java)
    private val rolePermissionRepository = Mockito.mock(RolePermissionRepository::class.java)
    private val auditService = Mockito.mock(AdminAuditService::class.java)
    private val service = KeycloakUserService(
        properties, userRepository, roleRepository, userRoleRepository, rolePermissionRepository, auditService
    )

    private val analista = RoleEntity(id = UUID.randomUUID(), name = "ANALISTA")
    private val gestor = RoleEntity(id = UUID.randomUUID(), name = "GESTOR")

    @BeforeEach
    fun setup() {
        Mockito.`when`(userRepository.save(Mockito.any(AppUserEntity::class.java))).thenAnswer { inv ->
            inv.getArgument<AppUserEntity>(0).also { if (it.id == null) it.id = UUID.randomUUID() }
        }
        Mockito.`when`(roleRepository.findAllByNameIn(setOf("ANALISTA"))).thenReturn(listOf(analista))
        Mockito.`when`(rolePermissionRepository.findPermissionCodesByRoleIds(listOfNotNull(analista.id)))
            .thenReturn(listOf("REPORT_READ", "REPORT_RUN"))
    }

    @Test
    fun `vincula pelo e-mail verificado e so le os perfis do client de login`() {
        val existing = AppUserEntity(id = UUID.randomUUID(), name = "Ana", email = "ana@rovema.com.br", active = true)
        Mockito.`when`(userRepository.findByEmailIgnoreCase("ana@rovema.com.br")).thenReturn(Optional.of(existing))

        val principal = service.principalFor(claims())

        assertEquals(existing.id, principal.userId)
        assertEquals(SUB, existing.keycloakId)
        assertEquals(
            setOf("ROLE_ANALISTA", "REPORT_READ", "REPORT_RUN"),
            principal.authorities.map { it.authority }.toSet()
        )
        val saved = ArgumentCaptor.forClass(UserRoleEntity::class.java)
        Mockito.verify(userRoleRepository).save(saved.capture())
        assertEquals("ANALISTA", saved.value.role?.name)
    }

    @Test
    fun `sem e-mail verificado nao vincula nem cria`() {
        val error = assertThrows(KeycloakLoginRejectedException::class.java) {
            service.principalFor(claims(emailVerified = false))
        }

        assertTrue(error.message.orEmpty().contains("nao confirmou o e-mail"))
        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any(AppUserEntity::class.java))
    }

    @Test
    fun `e-mail ja vinculado a outra conta do Keycloak e recusado`() {
        val other = AppUserEntity(id = UUID.randomUUID(), email = "ana@rovema.com.br", keycloakId = "outra-conta", active = true)
        Mockito.`when`(userRepository.findByEmailIgnoreCase("ana@rovema.com.br")).thenReturn(Optional.of(other))

        assertThrows(KeycloakLoginRejectedException::class.java) { service.principalFor(claims()) }
        assertEquals("outra-conta", other.keycloakId)
    }

    @Test
    fun `primeiro acesso de quem nao existia cria o espelho local sem senha`() {
        val principal = service.principalFor(claims())

        val created = ArgumentCaptor.forClass(AppUserEntity::class.java)
        Mockito.verify(userRepository, Mockito.atLeastOnce()).save(created.capture())
        val user = created.allValues.first()
        assertEquals(SUB, user.keycloakId)
        assertEquals("ana@rovema.com.br", user.email)
        assertEquals("Ana Souza", user.name)
        assertEquals("", user.passwordHash)
        assertEquals(user.id, principal.userId)
    }

    @Test
    fun `desativado no agroreports nao entra mesmo com token valido`() {
        val user = AppUserEntity(id = UUID.randomUUID(), email = "ana@rovema.com.br", keycloakId = SUB, active = false)
        Mockito.`when`(userRepository.findByKeycloakId(SUB)).thenReturn(Optional.of(user))

        assertThrows(KeycloakLoginRejectedException::class.java) { service.principalFor(claims()) }
    }

    @Test
    fun `perfil retirado no Keycloak sai da copia local no acesso seguinte`() {
        val user = AppUserEntity(id = UUID.randomUUID(), name = "Ana Souza", email = "ana@rovema.com.br", keycloakId = SUB, active = true)
        val oldLink = UserRoleEntity(id = UUID.randomUUID(), user = user, role = gestor)
        Mockito.`when`(userRepository.findByKeycloakId(SUB)).thenReturn(Optional.of(user))
        Mockito.`when`(userRoleRepository.findAllByUserId(user.id ?: error("sem id"))).thenReturn(listOf(oldLink))

        val authorities = service.principalFor(claims()).authorities.map { it.authority }

        Mockito.verify(userRoleRepository).delete(oldLink)
        assertFalse("ROLE_GESTOR" in authorities)
        assertTrue("ROLE_ANALISTA" in authorities)
    }

    private fun claims(emailVerified: Boolean = true): Claims =
        Jwts.claims()
            .subject(SUB)
            .add("email", "Ana@Rovema.com.br")
            .add("email_verified", emailVerified)
            .add("name", "Ana Souza")
            // Roles de realm e de outros clients nao valem aqui, mesmo com o mesmo nome.
            .add("realm_access", mapOf("roles" to listOf("ADMIN")))
            .add(
                "resource_access",
                mapOf(
                    "agroreports" to mapOf("roles" to listOf("analista")),
                    "sapvema" to mapOf("roles" to listOf("ADMIN"))
                )
            )
            .build()

    private companion object {
        const val SUB = "kc-ana-0001"
    }
}
