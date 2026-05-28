package com.chico.dbinspector.report

import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class ReportComparisonService {
    fun compareField(v1: Any?, v2: Any?, tolerance: BigDecimal? = null): FieldComparison =
        compareFieldValue(v1, v2, tolerance)

    fun normalizeTolerances(raw: Map<String, Double>): Map<String, BigDecimal> =
        raw.entries.associate { (field, tolerance) ->
            val key = field.trim().lowercase()
            require(key.isNotBlank()) { "Nome de campo de tolerancia nao pode ser vazio" }
            require(!tolerance.isNaN() && !tolerance.isInfinite() && tolerance >= 0.0) {
                "Tolerancia invalida para '$field': $tolerance"
            }
            key to BigDecimal.valueOf(tolerance)
        }

    fun buildKeyedDiff(
        source1: ComparisonSource,
        source2: ComparisonSource,
        comparisonKey: String,
        commonColumns: Set<String>,
        toleranceByField: Map<String, BigDecimal>
    ): ComparisonDiff {
        val rows1ByKey = source1.rows.groupBy { normalizeComparisonKeyValue(it[comparisonKey]) }
        val rows2ByKey = source2.rows.groupBy { normalizeComparisonKeyValue(it[comparisonKey]) }
        val duplicateKeysSource1 = rows1ByKey
            .filter { it.value.size > 1 }
            .mapKeys { it.key ?: "<null>" }
            .mapValues { it.value.size }
        val duplicateKeysSource2 = rows2ByKey
            .filter { it.value.size > 1 }
            .mapKeys { it.key ?: "<null>" }
            .mapValues { it.value.size }
        val allKeys = (rows1ByKey.keys + rows2ByKey.keys).distinct().sortedWith(compareBy(nullsFirst<String>()) { it })

        val onlyInSource1 = mutableListOf<Map<String, Any?>>()
        val onlyInSource2 = mutableListOf<Map<String, Any?>>()
        val withDifferences = mutableListOf<MatchedRecord>()
        var matchCount = 0
        var equalCount = 0
        var differentCount = 0

        allKeys.forEach { keyValue ->
            val list1 = (rows1ByKey[keyValue] ?: emptyList()).sortedBy { rowFingerprint(it, commonColumns) }
            val list2 = (rows2ByKey[keyValue] ?: emptyList()).sortedBy { rowFingerprint(it, commonColumns) }
            val matched = minOf(list1.size, list2.size)
            matchCount += matched

            for (i in 0 until matched) {
                val row1 = list1[i]
                val row2 = list2[i]
                val fields = commonColumns.associateWith { col ->
                    compareFieldValue(row1[col], row2[col], toleranceByField[col.lowercase()])
                }
                if (fields.values.all { it.equal }) {
                    equalCount++
                } else {
                    differentCount++
                    withDifferences += MatchedRecord(key = keyValue, fields = fields)
                }
            }

            if (list1.size > matched) onlyInSource1 += list1.drop(matched)
            if (list2.size > matched) onlyInSource2 += list2.drop(matched)
        }

        return ComparisonDiff(
            onlyInSource1 = onlyInSource1,
            onlyInSource2 = onlyInSource2,
            matchCount = matchCount,
            equalCount = equalCount,
            differentCount = differentCount,
            withDifferences = withDifferences,
            duplicateKeysSource1 = duplicateKeysSource1,
            duplicateKeysSource2 = duplicateKeysSource2,
            mode = "keyed"
        )
    }

    fun buildContentDiff(
        source1: ComparisonSource,
        source2: ComparisonSource,
        commonColumns: Set<String>,
        toleranceByField: Map<String, BigDecimal>
    ): ComparisonDiff {
        if (toleranceByField.isEmpty()) {
            val rows1ByContent = source1.rows.groupBy { rowFingerprint(it, commonColumns) }
            val rows2ByContent = source2.rows.groupBy { rowFingerprint(it, commonColumns) }
            val allFingerprints = (rows1ByContent.keys + rows2ByContent.keys).distinct()

            val onlyInSource1 = mutableListOf<Map<String, Any?>>()
            val onlyInSource2 = mutableListOf<Map<String, Any?>>()
            var matchCount = 0

            allFingerprints.forEach { fp ->
                val list1 = rows1ByContent[fp] ?: emptyList()
                val list2 = rows2ByContent[fp] ?: emptyList()
                val matched = minOf(list1.size, list2.size)
                matchCount += matched
                if (list1.size > matched) onlyInSource1 += list1.drop(matched)
                if (list2.size > matched) onlyInSource2 += list2.drop(matched)
            }

            return ComparisonDiff(
                onlyInSource1 = onlyInSource1,
                onlyInSource2 = onlyInSource2,
                matchCount = matchCount,
                equalCount = matchCount,
                differentCount = 0,
                withDifferences = emptyList(),
                mode = "content"
            )
        }

        val remainingSource2 = source2.rows.toMutableList()
        val onlyInSource1 = mutableListOf<Map<String, Any?>>()
        var matchCount = 0

        source1.rows.forEach { row1 ->
            val idx = remainingSource2.indexOfFirst { row2 -> rowsEqualWithTolerance(row1, row2, commonColumns, toleranceByField) }
            if (idx >= 0) {
                matchCount++
                remainingSource2.removeAt(idx)
            } else {
                onlyInSource1 += row1
            }
        }

        return ComparisonDiff(
            onlyInSource1 = onlyInSource1,
            onlyInSource2 = remainingSource2,
            matchCount = matchCount,
            equalCount = matchCount,
            differentCount = 0,
            withDifferences = emptyList(),
            mode = "content"
        )
    }

    private fun normalizeComparisonKeyValue(value: Any?): String? {
        val normalized = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return normalized?.lowercase()
    }

    private fun rowFingerprint(row: Map<String, Any?>, columns: Set<String>): String =
        columns.sorted().joinToString("|") { col ->
            "$col=${normalizeFingerprintValue(row[col])}"
        }

    private fun normalizeFingerprintValue(value: Any?): String {
        if (value == null) return "__NULL__"
        val bd = runCatching { toBigDecimal(value) }.getOrNull()
        if (bd != null) return bd.stripTrailingZeros().toPlainString()
        return value.toString().trim().lowercase()
    }

    private fun compareFieldValue(v1: Any?, v2: Any?, tolerance: BigDecimal? = null): FieldComparison {
        if (v1 == null && v2 == null) return FieldComparison(source1 = null, source2 = null, equal = true, diff = null)
        if (v1 == null || v2 == null) return FieldComparison(source1 = v1, source2 = v2, equal = false, diff = null)

        val bd1 = runCatching { toBigDecimal(v1) }.getOrNull()
        val bd2 = runCatching { toBigDecimal(v2) }.getOrNull()

        if (bd1 != null && bd2 != null) {
            val delta = (bd1 - bd2).abs()
            val equal = tolerance?.let { delta <= it } ?: (bd1.compareTo(bd2) == 0)
            val diff = if (equal) null else (bd1 - bd2).toDouble()
            return FieldComparison(source1 = v1, source2 = v2, equal = equal, diff = diff)
        }

        if (bd1 != null || bd2 != null) {
            return FieldComparison(source1 = v1, source2 = v2, equal = false, diff = null)
        }

        val equal = v1.toString().trim() == v2.toString().trim()
        return FieldComparison(source1 = v1, source2 = v2, equal = equal, diff = null)
    }

    private fun toBigDecimal(value: Any): BigDecimal =
        when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> value.trim().let { BigDecimal(it) }
            else -> throw IllegalArgumentException("valor nao numerico")
        }

    private fun rowsEqualWithTolerance(
        row1: Map<String, Any?>,
        row2: Map<String, Any?>,
        commonColumns: Set<String>,
        toleranceByField: Map<String, BigDecimal>
    ): Boolean =
        commonColumns.all { col ->
            compareFieldValue(row1[col], row2[col], toleranceByField[col.lowercase()]).equal
        }
}
