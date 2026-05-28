package com.chico.dbinspector.email

import org.springframework.stereotype.Service
import java.math.BigDecimal

data class EmailComparisonStats(
    val hasDifference: Boolean,
    val onlyInSource1: Int,
    val onlyInSource2: Int,
    val differentRows: Int,
    val matchedRows: Int
)

@Service
class EmailComparisonService {
    fun compareRows(
        sourceRows: List<Map<String, Any?>>, 
        source2Rows: List<Map<String, Any?>>, 
        comparisonKey: String?, 
        tolerances: Map<String, Double>
    ): EmailComparisonStats {
        val toleranceByField = tolerances.mapKeys { it.key.lowercase() }.mapValues { BigDecimal.valueOf(it.value) }
        return if (comparisonKey.isNullOrBlank()) {
            val remaining = source2Rows.toMutableList()
            var differentRows = 0
            var matchedRows = 0
            sourceRows.forEach { row1 ->
                val idx = remaining.indexOfFirst { row2 -> rowsEqualWithTolerance(row1, row2, toleranceByField) }
                if (idx >= 0) {
                    matchedRows++
                    remaining.removeAt(idx)
                } else {
                    differentRows++
                }
            }
            EmailComparisonStats(
                hasDifference = differentRows > 0 || remaining.isNotEmpty(),
                onlyInSource1 = differentRows,
                onlyInSource2 = remaining.size,
                differentRows = differentRows + remaining.size,
                matchedRows = matchedRows
            )
        } else {
            val rows1ByKey = sourceRows.groupBy { normalizeKey(it[comparisonKey]) }
            val rows2ByKey = source2Rows.groupBy { normalizeKey(it[comparisonKey]) }
            val allKeys = (rows1ByKey.keys + rows2ByKey.keys).distinct()
            var onlyInSource1 = 0
            var onlyInSource2 = 0
            var differentRows = 0
            var matchedRows = 0
            allKeys.forEach { key ->
                val l1 = rows1ByKey[key] ?: emptyList()
                val l2 = rows2ByKey[key] ?: emptyList()
                val matched = minOf(l1.size, l2.size)
                if (l1.size > matched) onlyInSource1 += l1.size - matched
                if (l2.size > matched) onlyInSource2 += l2.size - matched
                repeat(matched) { idx ->
                    if (rowsEqualWithTolerance(l1[idx], l2[idx], toleranceByField)) {
                        matchedRows++
                    } else {
                        differentRows++
                    }
                }
            }
            EmailComparisonStats(
                hasDifference = onlyInSource1 > 0 || onlyInSource2 > 0 || differentRows > 0,
                onlyInSource1 = onlyInSource1,
                onlyInSource2 = onlyInSource2,
                differentRows = differentRows,
                matchedRows = matchedRows
            )
        }
    }

    fun serializeTolerances(tolerances: Map<String, Double>): String =
        tolerances.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun parseTolerances(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0 || idx >= entry.lastIndex) return@mapNotNull null
                val key = entry.substring(0, idx).trim()
                val value = entry.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                if (key.isBlank()) return@mapNotNull null
                key to value
            }
            .toMap()
    }

    private fun rowsEqualWithTolerance(
        row1: Map<String, Any?>,
        row2: Map<String, Any?>,
        tolerances: Map<String, BigDecimal>
    ): Boolean {
        val allColumns = (row1.keys + row2.keys).toSet()
        return allColumns.all { col ->
            val tolerance = tolerances[col.lowercase()]
            valuesEqual(row1[col], row2[col], tolerance)
        }
    }

    private fun valuesEqual(v1: Any?, v2: Any?, tolerance: BigDecimal?): Boolean {
        if (v1 == null && v2 == null) return true
        if (v1 == null || v2 == null) return false
        val bd1 = runCatching { BigDecimal(v1.toString().trim()) }.getOrNull()
        val bd2 = runCatching { BigDecimal(v2.toString().trim()) }.getOrNull()
        if (bd1 != null && bd2 != null) {
            val delta = (bd1 - bd2).abs()
            return tolerance?.let { delta <= it } ?: (bd1.compareTo(bd2) == 0)
        }
        return v1.toString().trim().lowercase() == v2.toString().trim().lowercase()
    }

    private fun normalizeKey(value: Any?): String? {
        if (value == null) return null
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        val numeric = runCatching { BigDecimal(text) }.getOrNull()
        return numeric?.stripTrailingZeros()?.toPlainString() ?: text.lowercase()
    }
}
