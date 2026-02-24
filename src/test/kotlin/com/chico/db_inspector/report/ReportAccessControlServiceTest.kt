package com.chico.dbinspector.report

import com.chico.dbinspector.auth.AuthUserPrincipal
import com.chico.dbinspector.config.DbInspectorProperties
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
    private val properties = DbInspectorProperties().apply {
        security.aclDefaultDeny = false
    }

    private val service = ReportAccessControlService(
        folderAclRepository = folderAclRepository,
        reportAclRepository = reportAclRepository,
        properties = properties
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
    fun `canViewFolder should allow when report acl inside folder allows user`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("USER"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        val folderAclDeny = ReportFolderAclEntity(
            id = UUID.randomUUID(),
            subjectType = "USER",
            subjectKey = userId.toString(),
            canView = false,
            canRun = false
        )
        val reportAclAllow = ReportAclEntity(
            id = UUID.randomUUID(),
            subjectType = "USER",
            subjectKey = userId.toString(),
            canView = true,
            canRun = true
        )

        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(listOf(folderAclDeny))
        Mockito.`when`(reportAclRepository.findAllByReportFolderId(folderId)).thenReturn(listOf(reportAclAllow))

        assertTrue(service.canViewFolder(folderId))
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
            canView = true,
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
            canView = true,
            canRun = true
        )

        Mockito.`when`(reportAclRepository.findAllByReportId(reportId)).thenReturn(emptyList())
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(listOf(folderAcl))

        assertTrue(service.canRunReport(report))
    }

    @Test
    fun `canRunReport should deny when report acl matches user without run even if folder allows`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("USER"), permissions = emptySet())

        val folderId = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        val folder = ReportFolderEntity(id = folderId)
        val report = ReportEntity(id = reportId, folder = folder)

        val reportDeny = ReportAclEntity(
            id = UUID.randomUUID(),
            subjectType = "USER",
            subjectKey = userId.toString(),
            canView = false,
            canRun = false
        )
        val folderAllow = ReportFolderAclEntity(
            id = UUID.randomUUID(),
            folder = folder,
            subjectType = "USER",
            subjectKey = userId.toString(),
            canView = true,
            canRun = true
        )

        Mockito.`when`(reportAclRepository.findAllByReportId(reportId)).thenReturn(listOf(reportDeny))
        Mockito.`when`(folderAclRepository.findAllByFolderId(folderId)).thenReturn(listOf(folderAllow))

        assertFalse(service.canRunReport(report))
    }

    @Test
    fun `canViewReport should deny when report acl has view without run`() {
        val userId = UUID.randomUUID()
        authenticate(userId, roles = setOf("USER"), permissions = emptySet())

        val reportId = UUID.randomUUID()
        val report = ReportEntity(id = reportId)
        val reportAcl = ReportAclEntity(
            id = UUID.randomUUID(),
            subjectType = "USER",
            subjectKey = userId.toString(),
            canView = true,
            canRun = false
        )

        Mockito.`when`(reportAclRepository.findAllByReportId(reportId)).thenReturn(listOf(reportAcl))

        assertFalse(service.canViewReport(report))
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
            name = "User Test",
            email = "user@test.com",
            passwordHash = "hash",
            active = true,
            authoritiesList = authorities.map { it.authority }
        )

        val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
        SecurityContextHolder.getContext().authentication = auth
    }
}
