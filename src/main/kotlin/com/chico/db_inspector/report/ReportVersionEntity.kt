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
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

enum class ReportVersionStatus {
    DRAFT,
    // Rascunho que foi regravado: fica no historico, so quem edita enxerga, e pode ser retomado.
    REPLACED_DRAFT,
    PUBLISHED,
    SUPERSEDED,
    DISCARDED
}

enum class ReportVersionAuthorType {
    USER,
    AI_TOKEN,
    SYSTEM
}

/**
 * Foto completa de um relatorio: consulta, variaveis e JRXML. O ReportEntity continua sendo a
 * versao em uso (execucao, PDF, email e comparacao leem dele); publicar uma versao copia o
 * conteudo dela para la.
 */
@Entity
@Table(
    name = "report_versions",
    uniqueConstraints = [UniqueConstraint(name = "uk_report_version_number", columnNames = ["report_id", "version_number"])],
    indexes = [
        Index(name = "idx_report_versions_report", columnList = "report_id"),
        Index(name = "idx_report_versions_status", columnList = "status")
    ]
)
class ReportVersionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    var report: ReportEntity? = null,
    @Column(name = "version_number", nullable = false)
    var versionNumber: Int = 0,
    @Column(name = "base_version_number")
    var baseVersionNumber: Int? = null,
    @Column(nullable = false, length = 16)
    var status: String = ReportVersionStatus.DRAFT.name,
    @Column(nullable = false)
    var name: String = "",
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    @Column(name = "folder_id")
    var folderId: UUID? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    var sql: String = "",
    @Column(name = "second_sql", columnDefinition = "TEXT")
    var secondSql: String? = null,
    @Column(name = "comparison_key")
    var comparisonKey: String? = null,
    @Column(name = "variables_json", nullable = false, columnDefinition = "TEXT")
    var variablesJson: String = "[]",
    @Column(name = "jasper_template_id")
    var jasperTemplateId: UUID? = null,
    @Column(columnDefinition = "TEXT")
    var jrxml: String? = null,
    @Column(name = "author_type", nullable = false, length = 16)
    var authorType: String = ReportVersionAuthorType.USER.name,
    @Column(name = "author_id")
    var authorId: UUID? = null,
    @Column(name = "author_name", nullable = false)
    var authorName: String = "",
    @Column(columnDefinition = "TEXT")
    var note: String? = null,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
    // Sobe a cada regravacao do conteudo (sob o lock do relatorio). Publicar confere este numero.
    @Column(nullable = false, columnDefinition = "bigint not null default 1")
    var revision: Long = 1,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    @Column(name = "published_by")
    var publishedBy: String? = null
)
