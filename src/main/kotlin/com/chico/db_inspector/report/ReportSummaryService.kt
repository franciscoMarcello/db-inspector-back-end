package com.chico.dbinspector.report

import org.springframework.stereotype.Service

@Service
class ReportSummaryService {
    fun compute(columns: List<String>, rows: List<Map<String, Any?>>): List<ReportSummary> =
        columns.mapNotNull { column ->
            var sum = 0.0
            var hasValue = false
            var valid = true
            for (row in rows) {
                val value = row[column] ?: continue
                when (value) {
                    is Number -> {
                        sum += value.toDouble()
                        hasValue = true
                    }
                    else -> {
                        valid = false
                        break
                    }
                }
            }
            if (valid && hasValue) ReportSummary(column = column, sum = sum) else null
        }
}
