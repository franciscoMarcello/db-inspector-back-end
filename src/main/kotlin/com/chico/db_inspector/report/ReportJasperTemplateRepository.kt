package com.chico.dbinspector.report

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReportJasperTemplateRepository : JpaRepository<ReportJasperTemplateEntity, UUID> {
    fun existsByNameIgnoreCase(name: String): Boolean

    /** Trava o template: vincular um relatorio e decidir alterar o JRXML no lugar nao se cruzam. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReportJasperTemplateEntity t where t.id = :id")
    fun lockById(id: UUID): ReportJasperTemplateEntity?
}
