package com.chico.dbinspector.email

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EmailComparisonServiceTest {

    private val service = EmailComparisonService()

    @Test
    fun `deve detalhar linha presente somente no agromobi`() {
        val agromobi = listOf(
            mapOf("Chave" to "agricola.operacao|4092|INS0000777", "Custo Total" to "169.35"),
            mapOf("Chave" to "agricola.operacao|4093|INS0000777", "Custo Total" to "710.23")
        )
        val sap = listOf(
            mapOf("Chave" to "agricola.operacao|4092|INS0000777", "Custo Total" to "169.35")
        )

        val stats = service.compareRows(agromobi, sap, ComparisonOptions(comparisonKey = "Chave"))

        assertEquals(1, stats.onlyInSource1)
        assertEquals(0, stats.onlyInSource2)
        assertEquals(1, stats.differences.size)
        val difference = stats.differences.first()
        assertEquals(ComparisonDifferenceType.ONLY_IN_SOURCE1, difference.type)
        assertEquals("agricola.operacao|4093|INS0000777", difference.key)
        assertEquals("710.23", difference.row["Custo Total"])
    }

    @Test
    fun `deve detalhar campo divergente com delta numerico`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Quantidade" to "10", "Custo Total" to "100.50"))
        val sap = listOf(mapOf("Chave" to "K1", "Quantidade" to "10", "Custo Total" to "90.50"))

        val stats = service.compareRows(agromobi, sap, ComparisonOptions(comparisonKey = "Chave"))

        assertEquals(1, stats.differentRows)
        assertEquals(0, stats.matchedRows)
        assertEquals(1, stats.differences.size)
        val difference = stats.differences.first()
        assertEquals(ComparisonDifferenceType.DIVERGENT_VALUE, difference.type)
        assertEquals("Custo Total", difference.column)
        assertEquals("100.50", difference.value1)
        assertEquals("90.50", difference.value2)
        assertEquals(0, BigDecimal("10.00").compareTo(difference.delta))
    }

    @Test
    fun `nao deve gerar diferenca quando divergencia esta dentro da tolerancia`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.02"))
        val sap = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.00"))

        val stats = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(comparisonKey = "Chave", tolerances = mapOf("custo total" to 0.05))
        )

        assertEquals(1, stats.matchedRows)
        assertTrue(stats.differences.isEmpty())
        assertEquals(false, stats.hasDifference)
    }

    @Test
    fun `deve comparar somente as colunas do whitelist`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.00", "Data Apontamento" to "2026-08-01"))
        val sap = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.00", "Data Documento" to "2026-08-03"))

        val semWhitelist = service.compareRows(agromobi, sap, ComparisonOptions(comparisonKey = "Chave"))
        assertEquals(1, semWhitelist.differentRows)

        val comWhitelist = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(comparisonKey = "Chave", columns = listOf("Chave", "Custo Total"))
        )

        assertEquals(1, comWhitelist.matchedRows)
        assertEquals(0, comWhitelist.differentRows)
        assertEquals(false, comWhitelist.hasDifference)
    }

    @Test
    fun `deve ignorar as colunas da blacklist`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.00", "Data Apontamento" to "2026-08-01"))
        val sap = listOf(mapOf("Chave" to "K1", "Custo Total" to "100.00", "Data Documento" to "2026-08-03"))

        val stats = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(
                comparisonKey = "Chave",
                ignoreColumns = listOf("Data Apontamento", "Data Documento")
            )
        )

        assertEquals(1, stats.matchedRows)
        assertEquals(0, stats.differentRows)
    }

    @Test
    fun `whitelist deve prevalecer sobre blacklist`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Quantidade" to "10", "Custo Total" to "100.00"))
        val sap = listOf(mapOf("Chave" to "K1", "Quantidade" to "99", "Custo Total" to "100.00"))

        val stats = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(
                comparisonKey = "Chave",
                columns = listOf("Custo Total"),
                ignoreColumns = listOf("Custo Total")
            )
        )

        assertEquals(1, stats.matchedRows)
        assertTrue(stats.differences.isEmpty())
    }

    @Test
    fun `deve aplicar tolerancia percentual`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Custo Total" to "1000.00"))
        val sap = listOf(mapOf("Chave" to "K1", "Custo Total" to "995.00"))

        val dentro = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(comparisonKey = "Chave", tolerancePercents = mapOf("custo total" to 0.6))
        )
        assertEquals(1, dentro.matchedRows)

        val fora = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(comparisonKey = "Chave", tolerancePercents = mapOf("custo total" to 0.4))
        )
        assertEquals(1, fora.differentRows)
        assertEquals(0, BigDecimal("5.00").compareTo(fora.differences.first().delta))
    }

    @Test
    fun `tolerancia absoluta e percentual devem combinar pela mais permissiva`() {
        val agromobi = listOf(mapOf("Chave" to "K1", "Custo Total" to "1000.00"))
        val sap = listOf(mapOf("Chave" to "K1", "Custo Total" to "995.00"))

        val stats = service.compareRows(
            agromobi,
            sap,
            ComparisonOptions(
                comparisonKey = "Chave",
                tolerances = mapOf("custo total" to 0.02),
                tolerancePercents = mapOf("custo total" to 1.0)
            )
        )

        assertEquals(1, stats.matchedRows)
    }

    @Test
    fun `deve agregar por grupo somando delta e valor das linhas exclusivas`() {
        val stats = service.compareRows(agromobiComGrupos(), sapComGrupos(), ComparisonOptions(comparisonKey = "Chave"))

        val grupos = service.aggregate(stats.differences, groupByColumn = "Modelo", valueColumn = "Custo Total")

        assertEquals(listOf("A", "B"), grupos.map { it.group })
        val grupoA = grupos.first()
        assertEquals(1, grupoA.onlyInSource1)
        assertEquals(0, grupoA.onlyInSource2)
        assertEquals(1, grupoA.divergentValues)
        assertEquals(0, BigDecimal("10.00").compareTo(grupoA.deltaSum))
        assertEquals(0, BigDecimal("50.00").compareTo(grupoA.valueOnlyInSource1))
        val grupoB = grupos.last()
        assertEquals(1, grupoB.onlyInSource2)
        assertEquals(0, BigDecimal("30.00").compareTo(grupoB.valueOnlyInSource2))
    }

    @Test
    fun `deve agregar em linha unica TOTAL sem coluna de agrupamento`() {
        val stats = service.compareRows(agromobiComGrupos(), sapComGrupos(), ComparisonOptions(comparisonKey = "Chave"))

        val grupos = service.aggregate(stats.differences, groupByColumn = null, valueColumn = "Custo Total")

        assertEquals(1, grupos.size)
        assertEquals("TOTAL", grupos.first().group)
        assertEquals(1, grupos.first().onlyInSource1)
        assertEquals(1, grupos.first().onlyInSource2)
        assertEquals(1, grupos.first().divergentValues)
    }

    @Test
    fun `top diferencas deve ordenar pelo peso financeiro`() {
        val stats = service.compareRows(agromobiComGrupos(), sapComGrupos(), ComparisonOptions(comparisonKey = "Chave"))

        val top = service.topDifferences(stats.differences, valueColumn = "Custo Total", limit = 2)

        assertEquals(listOf("K2", "K4"), top.map { it.key })
        assertTrue(service.topDifferences(stats.differences, "Custo Total", limit = 0).isEmpty())
    }

    @Test
    fun `deve detalhar linhas sem chave de comparacao`() {
        val agromobi = listOf(mapOf("item" to "A"), mapOf("item" to "B"))
        val sap = listOf(mapOf("item" to "A"), mapOf("item" to "C"))

        val stats = service.compareRows(agromobi, sap, ComparisonOptions())

        assertEquals(1, stats.onlyInSource1)
        assertEquals(1, stats.onlyInSource2)
        assertEquals(
            listOf(ComparisonDifferenceType.ONLY_IN_SOURCE1, ComparisonDifferenceType.ONLY_IN_SOURCE2),
            stats.differences.map { it.type }
        )
        assertEquals("B", stats.differences.first().row["item"])
        assertEquals("C", stats.differences.last().row["item"])
    }

    private fun agromobiComGrupos() = listOf(
        mapOf("Chave" to "K1", "Modelo" to "A", "Custo Total" to "100.00"),
        mapOf("Chave" to "K2", "Modelo" to "A", "Custo Total" to "50.00"),
        mapOf("Chave" to "K3", "Modelo" to "B", "Custo Total" to "200.00")
    )

    private fun sapComGrupos() = listOf(
        mapOf("Chave" to "K1", "Modelo" to "A", "Custo Total" to "90.00"),
        mapOf("Chave" to "K3", "Modelo" to "B", "Custo Total" to "200.00"),
        mapOf("Chave" to "K4", "Modelo" to "B", "Custo Total" to "30.00")
    )
}
