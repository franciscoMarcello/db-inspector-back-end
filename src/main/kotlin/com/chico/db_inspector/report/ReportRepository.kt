package com.chico.dbinspector.report

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface ReportRepository : JpaRepository<ReportEntity, UUID> {
    @EntityGraph(attributePaths = ["variables", "folder", "jasperTemplate"])
    override fun findAll(sort: Sort): List<ReportEntity>

    @EntityGraph(attributePaths = ["variables", "folder", "jasperTemplate"])
    override fun findById(id: UUID): Optional<ReportEntity>

    fun existsByFolderId(folderId: UUID): Boolean
    fun existsByJasperTemplateId(jasperTemplateId: UUID): Boolean
    fun findAllByJasperTemplateId(jasperTemplateId: UUID): List<ReportEntity>

    @Query("select r.id from ReportEntity r where r.jasperTemplate.id = :templateId")
    fun findIdsByJasperTemplateId(templateId: UUID): List<UUID>

    /** Trava de aplicacao (advisory) ate o fim da transacao; ver ReportVersionService.lockWrites. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:key)) t", nativeQuery = true)
    fun advisoryLock(key: Long): Int

    /** Trava a linha do relatorio ate o fim da transacao: serializa as gravacoes de versao. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReportEntity r where r.id = :id")
    fun lockById(id: UUID): ReportEntity?
}
