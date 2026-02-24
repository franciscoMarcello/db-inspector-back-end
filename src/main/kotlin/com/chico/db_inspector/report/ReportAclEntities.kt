package com.chico.dbinspector.report

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(
    name = "report_folder_acl",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_report_folder_acl_subject",
            columnNames = ["folder_id", "subject_type", "subject_key"]
        )
    ],
    indexes = [
        Index(name = "idx_report_folder_acl_folder", columnList = "folder_id"),
        Index(name = "idx_report_folder_acl_subject", columnList = "subject_type, subject_key")
    ]
)
class ReportFolderAclEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    var folder: ReportFolderEntity? = null,
    @Column(name = "subject_type", nullable = false, length = 16)
    var subjectType: String = "",
    @Column(name = "subject_key", nullable = false, length = 120)
    var subjectKey: String = "",
    @Column(name = "can_view", nullable = false)
    var canView: Boolean = false,
    @Column(name = "can_run", nullable = false)
    var canRun: Boolean = false,
    @Column(name = "can_edit", nullable = false)
    var canEdit: Boolean = false,
    @Column(name = "can_delete", nullable = false)
    var canDelete: Boolean = false
)

@Entity
@Table(
    name = "report_acl",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_report_acl_subject",
            columnNames = ["report_id", "subject_type", "subject_key"]
        )
    ],
    indexes = [
        Index(name = "idx_report_acl_report", columnList = "report_id"),
        Index(name = "idx_report_acl_subject", columnList = "subject_type, subject_key")
    ]
)
class ReportAclEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    var report: ReportEntity? = null,
    @Column(name = "subject_type", nullable = false, length = 16)
    var subjectType: String = "",
    @Column(name = "subject_key", nullable = false, length = 120)
    var subjectKey: String = "",
    @Column(name = "can_view", nullable = false)
    var canView: Boolean = false,
    @Column(name = "can_run", nullable = false)
    var canRun: Boolean = false,
    @Column(name = "can_edit", nullable = false)
    var canEdit: Boolean = false,
    @Column(name = "can_delete", nullable = false)
    var canDelete: Boolean = false
)
