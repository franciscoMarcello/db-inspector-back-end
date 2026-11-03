package com.chico.dbinspector.email

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext

enum class ComparisonDifferenceType {
    ONLY_IN_SOURCE1,
    ONLY_IN_SOURCE2,
    DIVERGENT_VALUE
}

data class ComparisonOptions(
    val comparisonKey: String? = null,
    val tolerances: Map<String, Double> = emptyMap(),
    val tolerancePercents: Map<String, Double> = emptyMap(),
    val columns: List<String> = emptyList(),
    val ignoreColumns: List<String> = emptyList()
)

data class EmailComparisonDifference(
    val type: ComparisonDifferenceType,
    val key: String?,
    val column: String? = null,
    val value1: Any? = null,
    val value2: Any? = null,
    val delta: BigDecimal? = null,
    val row: Map<String, Any?> = emptyMap()
)

/**
 * `deltaSum` soma o modulo dos deltas para nao deixar +5 e -5 se anularem e esconder divergencia.
 * Os valores exclusivos somam o valor bruto, porque estorno negativo reduz o total de verdade.
 */
data class ComparisonGroupSummary(
    val group: String,
    val onlyInSource1: Int,
    val onlyInSource2: Int,
    val divergentValues: Int,
    val deltaSum: BigDecimal,
    val valueOnlyInSource1: BigDecimal,
    val valueOnlyInSource2: BigDecimal
)

data class ComparisonEmailDetail(
    val source1Label: String,
    val source2Label: String,
    val valueColumn: String?,
    val topDifferences: List<EmailComparisonDifference>,
    val groups: List<ComparisonGroupSummary>
)

data class EmailComparisonStats(
    val hasDifference: Boolean,
    val onlyInSource1: Int,
    val onlyInSource2: Int,
    val differentRows: Int,
    val matchedRows: Int,
    val differences: List<EmailComparisonDifference> = emptyList()
)

@Service
class EmailComparisonService {
    fun compareRows(
        sourceRows: List<Map<String, Any?>>,
        source2Rows: List<Map<String, Any?>>,
        options: ComparisonOptions
    ): EmailComparisonStats {
        val rules = ComparisonRules(options)
        val comparisonKey = options.comparisonKey
        val differences = mutableListOf<EmailComparisonDifference>()
        return if (comparisonKey.isNullOrBlank()) {
            val remaining = source2Rows.toMutableList()
            var differentRows = 0
            var matchedRows = 0
            sourceRows.forEach { row1 ->
                val idx = remaining.indexOfFirst { row2 -> rules.divergentColumns(row1, row2).isEmpty() }
                if (idx >= 0) {
                    matchedRows++
                    remaining.removeAt(idx)
                } else {
                    differentRows++
                    differences.addDetail(
                        EmailComparisonDifference(
                            type = ComparisonDifferenceType.ONLY_IN_SOURCE1,
                            key = null,
                            row = row1
                        )
                    )
                }
            }
            remaining.forEach { row2 ->
                differences.addDetail(
                    EmailComparisonDifference(
                        type = ComparisonDifferenceType.ONLY_IN_SOURCE2,
                        key = null,
                        row = row2
                    )
                )
            }
            EmailComparisonStats(
                hasDifference = differentRows > 0 || remaining.isNotEmpty(),
                onlyInSource1 = differentRows,
                onlyInSource2 = remaining.size,
                differentRows = differentRows + remaining.size,
                matchedRows = matchedRows,
                differences = differences
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
                if (l1.size > matched) {
                    onlyInSource1 += l1.size - matched
                    l1.drop(matched).forEach { row ->
                        differences.addDetail(
                            EmailComparisonDifference(
                                type = ComparisonDifferenceType.ONLY_IN_SOURCE1,
                                key = rawKey(row, comparisonKey),
                                row = row
                            )
                        )
                    }
                }
                if (l2.size > matched) {
                    onlyInSource2 += l2.size - matched
                    l2.drop(matched).forEach { row ->
                        differences.addDetail(
                            EmailComparisonDifference(
                                type = ComparisonDifferenceType.ONLY_IN_SOURCE2,
                                key = rawKey(row, comparisonKey),
                                row = row
                            )
                        )
                    }
                }
                repeat(matched) { idx ->
                    val divergent = rules.divergentColumns(l1[idx], l2[idx])
                    if (divergent.isEmpty()) {
                        matchedRows++
                    } else {
                        differentRows++
                        divergent.forEach { column ->
                            val value1 = l1[idx][column]
                            val value2 = l2[idx][column]
                            differences.addDetail(
                                EmailComparisonDifference(
                                    type = ComparisonDifferenceType.DIVERGENT_VALUE,
                                    key = rawKey(l1[idx], comparisonKey),
                                    column = column,
                                    value1 = value1,
                                    value2 = value2,
                                    delta = numericDelta(value1, value2),
                                    row = l1[idx]
                                )
                            )
                        }
                    }
                }
            }
            EmailComparisonStats(
                hasDifference = onlyInSource1 > 0 || onlyInSource2 > 0 || differentRows > 0,
                onlyInSource1 = onlyInSource1,
                onlyInSource2 = onlyInSource2,
                differentRows = differentRows,
                matchedRows = matchedRows,
                differences = differences
            )
        }
    }

    /** Colunas que a comparacao considera, dado o whitelist/blacklist das opcoes. */
    fun comparedColumns(
        columns1: List<String>,
        columns2: List<String>,
        options: ComparisonOptions
    ): List<String> = ComparisonRules(options).resolveColumns(columns1 + columns2)

    fun aggregate(
        differences: List<EmailComparisonDifference>,
        groupByColumn: String?,
        valueColumn: String?
    ): List<ComparisonGroupSummary> {
        if (differences.isEmpty()) return emptyList()
        val grouped = differences.groupBy { difference ->
            if (groupByColumn.isNullOrBlank()) TOTAL_GROUP
            else difference.row[groupByColumn]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: EMPTY_GROUP
        }
        return grouped.map { (group, items) ->
            ComparisonGroupSummary(
                group = group,
                onlyInSource1 = items.count { it.type == ComparisonDifferenceType.ONLY_IN_SOURCE1 },
                onlyInSource2 = items.count { it.type == ComparisonDifferenceType.ONLY_IN_SOURCE2 },
                divergentValues = items.count { it.type == ComparisonDifferenceType.DIVERGENT_VALUE },
                deltaSum = items
                    .filter { it.type == ComparisonDifferenceType.DIVERGENT_VALUE && matchesValueColumn(it.column, valueColumn) }
                    .fold(BigDecimal.ZERO) { acc, item -> acc + (item.delta?.abs() ?: BigDecimal.ZERO) },
                valueOnlyInSource1 = sumValueColumn(items, ComparisonDifferenceType.ONLY_IN_SOURCE1, valueColumn),
                valueOnlyInSource2 = sumValueColumn(items, ComparisonDifferenceType.ONLY_IN_SOURCE2, valueColumn)
            )
        }.sortedWith(
            compareByDescending<ComparisonGroupSummary> { groupWeight(it) }
                .thenByDescending { it.onlyInSource1 + it.onlyInSource2 + it.divergentValues }
                .thenBy { it.group }
        )
    }

    /**
     * Ordena por peso financeiro para o topo do email: delta do campo divergente, ou o valor da
     * linha quando ela existe em uma origem so. Sem `valueColumn` o peso e' so o delta.
     */
    fun topDifferences(
        differences: List<EmailComparisonDifference>,
        valueColumn: String?,
        limit: Int
    ): List<EmailComparisonDifference> {
        if (limit <= 0) return emptyList()
        return differences
            .sortedByDescending { differenceWeight(it, valueColumn) }
            .take(limit)
    }

    fun buildDetail(
        differences: List<EmailComparisonDifference>,
        source1Label: String,
        source2Label: String,
        groupByColumn: String?,
        valueColumn: String?,
        topN: Int
    ): ComparisonEmailDetail? {
        if (differences.isEmpty()) return null
        val groups = aggregate(differences, groupByColumn, valueColumn)
        val top = topDifferences(differences, valueColumn, topN)
        if (groups.isEmpty() && top.isEmpty()) return null
        return ComparisonEmailDetail(
            source1Label = source1Label,
            source2Label = source2Label,
            valueColumn = valueColumn,
            topDifferences = top,
            groups = groups
        )
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

    fun serializeColumns(columns: List<String>): String =
        columns.mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }.joinToString(";")

    fun parseColumns(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }
    }

    private fun matchesValueColumn(column: String?, valueColumn: String?): Boolean {
        if (valueColumn.isNullOrBlank()) return true
        return column?.equals(valueColumn, ignoreCase = true) == true
    }

    private fun sumValueColumn(
        items: List<EmailComparisonDifference>,
        type: ComparisonDifferenceType,
        valueColumn: String?
    ): BigDecimal {
        if (valueColumn.isNullOrBlank()) return BigDecimal.ZERO
        return items
            .filter { it.type == type }
            .fold(BigDecimal.ZERO) { acc, item -> acc + (rowValue(item.row, valueColumn) ?: BigDecimal.ZERO) }
    }

    private fun groupWeight(summary: ComparisonGroupSummary): BigDecimal =
        summary.deltaSum + summary.valueOnlyInSource1.abs() + summary.valueOnlyInSource2.abs()

    private fun differenceWeight(difference: EmailComparisonDifference, valueColumn: String?): BigDecimal =
        when (difference.type) {
            ComparisonDifferenceType.DIVERGENT_VALUE -> difference.delta?.abs() ?: BigDecimal.ZERO
            else -> valueColumn?.let { rowValue(difference.row, it)?.abs() } ?: BigDecimal.ZERO
        }

    private fun rowValue(row: Map<String, Any?>, column: String): BigDecimal? {
        val raw = row.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value ?: return null
        return runCatching { BigDecimal(raw.toString().trim()) }.getOrNull()
    }

    private fun numericDelta(v1: Any?, v2: Any?): BigDecimal? {
        val bd1 = v1?.let { runCatching { BigDecimal(it.toString().trim()) }.getOrNull() } ?: return null
        val bd2 = v2?.let { runCatching { BigDecimal(it.toString().trim()) }.getOrNull() } ?: return null
        return bd1 - bd2
    }

    private fun normalizeKey(value: Any?): String? {
        if (value == null) return null
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        val numeric = runCatching { BigDecimal(text) }.getOrNull()
        return numeric?.stripTrailingZeros()?.toPlainString() ?: text.lowercase()
    }

    private fun rawKey(row: Map<String, Any?>, comparisonKey: String): String? =
        row[comparisonKey]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun MutableList<EmailComparisonDifference>.addDetail(difference: EmailComparisonDifference) {
        if (size < MAX_DIFFERENCE_DETAILS) add(difference)
    }

    /** Opcoes normalizadas uma vez por comparacao, para nao repetir lowercase linha a linha. */
    private class ComparisonRules(options: ComparisonOptions) {
        private val toleranceByField = options.tolerances.normalize()
        private val percentByField = options.tolerancePercents.normalize()
        private val whitelist = options.columns.toLowercaseSet()
        private val blacklist = options.ignoreColumns.toLowercaseSet()

        fun resolveColumns(columns: Collection<String>): List<String> {
            val distinct = columns.distinct()
            return when {
                whitelist.isNotEmpty() -> distinct.filter { it.lowercase() in whitelist }
                blacklist.isNotEmpty() -> distinct.filterNot { it.lowercase() in blacklist }
                else -> distinct
            }
        }

        fun divergentColumns(row1: Map<String, Any?>, row2: Map<String, Any?>): List<String> =
            resolveColumns(row1.keys + row2.keys).filterNot { col ->
                val key = col.lowercase()
                valuesEqual(row1[col], row2[col], toleranceByField[key], percentByField[key])
            }

        private fun valuesEqual(v1: Any?, v2: Any?, tolerance: BigDecimal?, percent: BigDecimal?): Boolean {
            if (v1 == null && v2 == null) return true
            if (v1 == null || v2 == null) return false
            val bd1 = runCatching { BigDecimal(v1.toString().trim()) }.getOrNull()
            val bd2 = runCatching { BigDecimal(v2.toString().trim()) }.getOrNull()
            if (bd1 != null && bd2 != null) {
                if (tolerance == null && percent == null) return bd1.compareTo(bd2) == 0
                val allowed = maxOf(tolerance ?: BigDecimal.ZERO, percentAllowance(bd1, bd2, percent))
                return (bd1 - bd2).abs() <= allowed
            }
            return v1.toString().trim().lowercase() == v2.toString().trim().lowercase()
        }

        private fun percentAllowance(bd1: BigDecimal, bd2: BigDecimal, percent: BigDecimal?): BigDecimal {
            if (percent == null || percent.signum() == 0) return BigDecimal.ZERO
            val reference = maxOf(bd1.abs(), bd2.abs())
            return reference.multiply(percent).divide(ONE_HUNDRED, MathContext.DECIMAL64)
        }

        private fun Map<String, Double>.normalize(): Map<String, BigDecimal> =
            entries.associate { it.key.trim().lowercase() to BigDecimal.valueOf(it.value) }

        private fun List<String>.toLowercaseSet(): Set<String> =
            mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() }?.lowercase() }.toSet()
    }

    private companion object {
        const val MAX_DIFFERENCE_DETAILS = 50_000
        const val TOTAL_GROUP = "TOTAL"
        const val EMPTY_GROUP = "(sem valor)"
        val ONE_HUNDRED: BigDecimal = BigDecimal("100")
    }
}
