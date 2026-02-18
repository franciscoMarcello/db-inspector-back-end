package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AuthUserPrincipal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class ReportAccessControlServiceTest {

    private val folderAclRepository = Mockito.mock(ReportFolderAclRepository::class.java)
    private val reportAclRepository = Mockito.mock(ReportAclRepository::class.java)

    private val service = ReportAccessControlService(
        folderAclRepository = folderAclRepository,
        reportAclRepository = reportAclRepository
    )

    @AfterEach
    fun cleanupSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `canViewFolder should allow when no acl exists`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("USER"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(emptyList())

        assertTrue(service.canViewFolder(folderId))
    }

    @Test
    fun `canViewFolder should deny when acl exists for other subject`() {
        val currentUserId = UUID.randomUUID()
        authenticate(currentUserId, roles = setOf("USER"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        val anotherUserAcl = ReportFolderAclEntity(
            id = UUID.randomUUID(),
            subjectType = "USER",
            subjectKey = UUID.randomUUID().toString(),
            canView = true
        )
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(listOf(anotherUserAcl))

        assertFalse(service.canViewFolder(folderId))
    }

    @Test
    fun `canRunReport should allow by role acl`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("MANAGER"), permissions = emptySet())

        val reportId = UUID.randomUUID()
        val report = ReportEntity(id = reportId)

        val roleAcl = ReportAclEntity(
            id = UUID.randomUUID(),
            subjectType = "ROLE",
            subjectKey = "MANAGER",
            canRun = true
        )
        Mockito.`when`(reportAclRepository.findAllByReportId(reportId)).thenReturn(listOf(roleAcl))

        assertTrue(service.canRunReport(report))
    }

    @Test
    fun `canRunReport should inherit from folder acl`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("USER"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        val folder = ReportFolderEntity(id = folderId)
        val report = ReportEntity(id = reportId, folder = folder)

        val folderAcl = ReportFolderAclEntity(
            id = UUID.randomUUID(),
            folder = folder,
            subjectType = "USER",
            subjectKey = userId.toString(),
            canRun = true
        )

        Mockito.`when`(reportAclRepository.findAllByReportId(reportId)).thenReturn(emptyList())
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(listOf(folderAcl))

        assertTrue(service.canRunReport(report))
    }

    @Test
    fun `admin should bypass acl restrictions`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("ADMIN"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(
            listOf(
                ReportFolderAclEntity(
                    id = UUID.randomUUID(),
                    subjectType = "USER",
                    subjectKey = UUID.randomUUID().toString(),
                    canView = false,
                    canRun = false,
                    canEdit = false,
                    canDelete = false
                )
            )
        )

        assertTrue(service.canDeleteFolder(folderId))
    }

    private fun authenticate(userId: UUID, roles: Set<String>, permissions: Set<String>) {
        val authorities = mutableListOf<SimpleGrantedAuthority>()
        authorities += roles.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
        authorities += permissions.map { SimpleGrantedAuthority(it) }

        val principal = AuthUserPrincipal(
            userId = userId,
            email = "user@test.com",
            passwordHash = "hash",
            active = true,
            authoritiesList = authorities.map { it.authority }
        )

        val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
        SecurityContextHolder.getContext().authentication = auth
    }
}
