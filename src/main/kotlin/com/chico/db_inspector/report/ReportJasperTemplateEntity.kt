package com.chico.dbinspector.report

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "report_jasper_templates")
class ReportJasperTemplateEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, unique = true)
    var name: String = "",
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    var jrxml: String = "",
    @Column(nullable = false)
    var archived: Boolean = false,
    @OneToMany(mappedBy = "jasperTemplate", fetch = jakarta.persistence.FetchType.LAZY)
    var reports: MutableList<ReportEntity> = mutableListOf(),
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: Instant? = null
)
