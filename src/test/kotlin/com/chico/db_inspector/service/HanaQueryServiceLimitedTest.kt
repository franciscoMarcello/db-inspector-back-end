package com.chico.dbinspector.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import javax.sql.DataSource

class HanaQueryServiceLimitedTest {

    private val dataSource = Mockito.mock(DataSource::class.java)
    private val connection = Mockito.mock(Connection::class.java)
    private val statement = Mockito.mock(PreparedStatement::class.java)
    private val resultSet = Mockito.mock(ResultSet::class.java)
    private val service = HanaQueryService(JdbcTemplate(dataSource))

    private fun wireConnection() {
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.autoCommit).thenReturn(true)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
    }

    @Test
    fun `deve barrar SQL de escrita sem abrir conexao com o SAP`() {
        assertThrows<IllegalArgumentException> { service.execLimited("SELECT * FROM OIGE FOR UPDATE", 10, 5) }
        assertThrows<IllegalArgumentException> { service.execLimited("SELECT 1 FROM DUMMY; DELETE FROM OIGE", 10, 5) }

        Mockito.verifyNoInteractions(dataSource)
    }

    @Test
    fun `deve rodar em transacao desfeita com limite de linhas e timeout`() {
        wireConnection()
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.metaData).thenReturn(meta)
        Mockito.`when`(meta.columnCount).thenReturn(1)
        Mockito.`when`(meta.getColumnLabel(1)).thenReturn("DocNum")
        Mockito.`when`(resultSet.next()).thenReturn(true, false)
        Mockito.`when`(resultSet.getObject(1)).thenReturn(42)

        val result = service.execLimited("SELECT \"DocNum\" FROM OIGE", 3, 7)

        assertEquals(listOf(mapOf("DocNum" to 42)), result.rows)
        val order = Mockito.inOrder(connection, statement)
        order.verify(connection).autoCommit = false
        order.verify(statement).maxRows = 3
        order.verify(statement).queryTimeout = 7
        order.verify(connection).rollback()
        order.verify(connection).autoCommit = true
        Mockito.verify(connection, Mockito.never()).commit()
    }

    @Test
    fun `deve desfazer a transacao mesmo quando a consulta falha`() {
        wireConnection()
        Mockito.`when`(statement.executeQuery()).thenThrow(SQLException("invalid column name: X"))

        val ex = assertThrows<ResponseStatusException> { service.execLimited("SELECT X FROM OIGE", 3, 7) }

        assertEquals(502, ex.statusCode.value())
        Mockito.verify(connection).rollback()
        Mockito.verify(connection, Mockito.never()).commit()
    }

    @Test
    fun `deve mascarar coluna com nome de senha que chegue no resultado`() {
        wireConnection()
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.metaData).thenReturn(meta)
        Mockito.`when`(meta.columnCount).thenReturn(2)
        Mockito.`when`(meta.getColumnLabel(1)).thenReturn("USER_CODE")
        Mockito.`when`(meta.getColumnLabel(2)).thenReturn("SENHA")
        Mockito.`when`(resultSet.next()).thenReturn(true, false)
        Mockito.`when`(resultSet.getObject(1)).thenReturn("manager")
        Mockito.`when`(resultSet.getObject(2)).thenReturn("hash-real")

        val result = service.execLimited("SELECT \"USER_CODE\", 'x' AS c FROM OUSR", 3, 7)

        assertEquals(listOf(mapOf("USER_CODE" to "manager", "SENHA" to "***")), result.rows)
        Mockito.verify(resultSet, Mockito.never()).getObject(2)
    }

}
