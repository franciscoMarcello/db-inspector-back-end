package com.chico.dbinspector.dashboard

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

enum class DashboardSystem {
    AGROMOBI,
    SAP;

    @JsonValue
    fun toJson(): String = name.lowercase()

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): DashboardSystem =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("System invalido: $value")
    }
}

enum class WidgetType {
    KPI,
    BAR,
    LINE,
    PIE,
    TABLE;

    @JsonValue
    fun toJson(): String = name.lowercase()

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): WidgetType =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Widget type invalido: $value")
    }
}

@Entity
@Table(name = "dashboards")
class DashboardEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false)
    var name: String = "",
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var system: DashboardSystem = DashboardSystem.AGROMOBI,
    @Column(nullable = false)
    var archived: Boolean = false,
    @Column(name = "filters_json", columnDefinition = "TEXT")
    var filtersJson: String? = null,
    @Column(name = "created_by", nullable = false, length = 255)
    var createdBy: String = "",
    @OneToMany(mappedBy = "dashboard", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var widgets: MutableList<DashboardWidgetEntity> = mutableListOf(),
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: Instant? = null
)

@Entity
@Table(name = "dashboard_widgets")
class DashboardWidgetEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dashboard_id", nullable = false)
    var dashboard: DashboardEntity? = null,
    @Column(nullable = false)
    var title: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: WidgetType = WidgetType.TABLE,
    @Column(name = "query_sql", nullable = false, columnDefinition = "TEXT")
    var querySql: String = "",
    @Column(name = "config_json", columnDefinition = "TEXT")
    var configJson: String? = null,
    @Column(name = "layout_json", columnDefinition = "TEXT")
    var layoutJson: String? = null,
    @Column(name = "position_order", nullable = false)
    var positionOrder: Int = 0,
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp
    @Column(nullable = false)
    var updatedAt: Instant? = null
)
