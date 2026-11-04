package com.chico.dbinspector.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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


    private fun failWith(ex: SQLException): String {
        wireConnection()
        Mockito.doThrow(ex).`when`(statement).executeQuery()
        return assertThrows<ResponseStatusException> { service.execLimited("SELECT \"X\" FROM OIGE", 3, 7) }.reason.orEmpty()
    }

    @Test
    fun `deve devolver erro de SQL do HANA sem endereco de conexao`() {
        val reason = failWith(SQLException("SAP DBTech JDBC: [260]: invalid column name: X at jdbc:sap://10.117.163.230:30015", "HY000", 260))

        assertEquals("Erro no SAP HANA [260]: invalid column name: X at [conexao]", reason)
    }

    @Test
    fun `deve unificar tabela inexistente e falta de permissao`() {
        val inexistente = failWith(SQLException("SAP DBTech JDBC: [259]: invalid table name: B1ADMIN.X", "HY000", 259))
        val semPermissao = failWith(SQLException("SAP DBTech JDBC: [258]: insufficient privilege: Not authorized", "HY000", 258))

        assertEquals(inexistente, semPermissao)
        assertFalse(inexistente.contains("B1ADMIN"))
    }

    @Test
    fun `deve esconder detalhe de falha de conexao`() {
        val reason = failWith(SQLException("SAP DBTech JDBC: [10709]: Connection failed (RTE:[89006] 10.117.163.230:30015)", "08S01", 10709))

        assertFalse(reason.contains("10.117"))
        assertFalse(reason.contains("RTE"))
    }

    @Test
    fun `tela de pessoa deve barrar efeito colateral sem abrir conexao`() {
        listOf("SELECT * FROM OIGE FOR SHARE LOCK", "SELECT B1ADMIN.SEQ.NEXTVAL FROM DUMMY", "SELECT * INTO T FROM OIGE").forEach { sql ->
            assertThrows<IllegalArgumentException>(sql) { service.exec(sql) }
        }

        Mockito.verifyNoInteractions(dataSource)
    }

    @Test
    fun `tela de pessoa deve rodar em transacao desfeita e manter chave sem diferenciar maiusculas`() {
        wireConnection()
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.metaData).thenReturn(meta)
        Mockito.`when`(meta.columnCount).thenReturn(1)
        Mockito.`when`(meta.getColumnLabel(1)).thenReturn("DOC")
        Mockito.`when`(resultSet.next()).thenReturn(true, false)
        Mockito.`when`(resultSet.getObject(1)).thenReturn(7)

        val result = service.exec("SELECT \"DocNum\" AS doc FROM OIGE -- comentario continua valendo para pessoa")

        assertEquals(listOf("DOC"), result.columns)
        assertEquals(7, result.rows.single()["doc"])
        Mockito.verify(connection).rollback()
        Mockito.verify(connection, Mockito.never()).commit()
    }

}
