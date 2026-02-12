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
    name = "report_variables",
    uniqueConstraints = [UniqueConstraint(name = "uk_report_variable_key", columnNames = ["report_id", "variable_key"])],
    indexes = [Index(name = "idx_report_variables_report_order", columnList = "report_id, order_index")]
)
class ReportVariableEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    var report: ReportEntity? = null,
    @Column(name = "variable_key", nullable = false)
    var key: String = "",
    @Column(nullable = false)
    var label: String = "",
    @Column(nullable = false)
    var type: String = "",
    @Column(nullable = false)
    var required: Boolean = true,
    @Column(name = "default_value", columnDefinition = "TEXT")
    var defaultValue: String? = null,
    @Column(name = "options_sql", columnDefinition = "TEXT")
    var optionsSql: String? = null,
    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0
)
