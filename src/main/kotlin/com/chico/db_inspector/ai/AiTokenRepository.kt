package com.chico.dbinspector.ai

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AiTokenRepository : JpaRepository<AiTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): AiTokenEntity?
    fun findAllByOrderByCreatedAtDesc(): List<AiTokenEntity>
}
