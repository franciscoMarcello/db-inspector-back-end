package com.chico.dbinspector.report

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reports")
class ReportEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var templateName: String = "",
    @Column(nullable = false, columnDefinition = "TEXT")
    var sql: String = "",
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    @Column(nullable = false)
    var archived: Boolean = false,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    var folder: ReportFolderEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jasper_template_id")
    var jasperTemplate: ReportJasperTemplateEntity? = null,
    @OneToMany(mappedBy = "report", cascade = [jakarta.persistence.CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 100)
    var variables: MutableList<ReportVariableEntity> = mutableListOf(),
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: Instant? = null
) {
    fun replaceVariables(newVariables: List<ReportVariableEntity>) {
        val existingByKey = variables.associateBy { it.key.lowercase() }.toMutableMap()
        val merged = mutableListOf<ReportVariableEntity>()

        newVariables.forEach { incoming ->
            val normalizedKey = incoming.key.lowercase()
            val target = existingByKey.remove(normalizedKey) ?: ReportVariableEntity()

            target.key = incoming.key
            target.label = incoming.label
            target.type = incoming.type
            target.required = incoming.required
            target.defaultValue = incoming.defaultValue
            target.orderIndex = incoming.orderIndex
            target.report = this

            merged.add(target)
        }

        variables.clear()
        variables.addAll(merged)
    }
}
