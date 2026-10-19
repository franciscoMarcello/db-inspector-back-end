package com.chico.dbinspector.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

class AiJrxmlGuardTest {

    private val guard = AiJrxmlGuard()

    private fun template(expression: String, extra: String = "", fieldClass: String = "java.lang.String") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jasperReport name="t" language="java" pageWidth="595" pageHeight="842" columnWidth="555" leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
            <parameter name="FMT" class="com.chico.dbinspector.report.JasperFormat"/>
            <parameter name="LOGO_STREAM" class="java.io.InputStream"/>
            <field name="valor" class="$fieldClass"/>
            $extra
            <detail>
                <band height="20">
                    <element kind="textField" x="0" y="0" width="200" height="20">
                        <expression><![CDATA[$expression]]></expression>
                    </element>
                </band>
            </detail>
        </jasperReport>
    """.trimIndent()

    private fun rules(jrxml: String) = guard.problems(jrxml).map { it.regra }.toSet()

    @Test
    fun `modelo sintetico com os padroes dos templates reais passa na guarda e compila`() {
        val resource = PathMatchingResourcePatternResolver().getResources("classpath:ai/instrucoes/exemplos/*/template.jrxml")
        assertEquals(1, resource.size)
        assertAllPassAndCompile(resource.toList().map { it.inputStream.bufferedReader().use { r -> r.readText() } to it.url.toString() })
    }

    /**
     * Regressao com os templates REAIS (que ficam fora do git, na pasta de contexto). So roda onde a
     * pasta existe: informe DBINSPECTOR_AI_CONTEXT_PATH. Garante que a guarda nao quebra relatorio legitimo.
     */
    @Test
    fun `templates reais da pasta de contexto passam na guarda e compilam`() {
        val context = System.getenv("DBINSPECTOR_AI_CONTEXT_PATH")?.takeIf { it.isNotBlank() }
        org.junit.jupiter.api.Assumptions.assumeTrue(context != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(context, "exemplos")))
        val files = java.nio.file.Files.walk(java.nio.file.Path.of(context!!, "exemplos")).use { stream ->
            stream.filter { it.fileName.toString() == "template.jrxml" }.toList()
        }
        assertTrue(files.isNotEmpty())
        assertAllPassAndCompile(files.map { java.nio.file.Files.readString(it) to it.toString() })
    }

    private fun assertAllPassAndCompile(templates: List<Pair<String, String>>) {
        templates.forEach { (jrxml, origin) ->
            assertEquals(emptyList<AiProblem>(), guard.problems(jrxml), origin)
            val (compiled, problems) = guard.compile(jrxml)
            assertEquals(emptyList<AiProblem>(), problems, origin)
            assertNotNull(compiled)
        }
    }

    @Test
    fun `helper FMT e metodos simples sao aceitos e compilam`() {
        val jrxml = template("\$P{FMT}.moeda(\$F{valor}) + \" / \" + String.valueOf(\$F{valor}).trim().toUpperCase()")

        assertEquals(emptySet<String>(), rules(jrxml))
        assertEquals(emptyList<AiProblem>(), guard.compile(jrxml).second)
    }

    @Test
    fun `deve recusar execucao de comando`() {
        assertTrue("expressao_nao_permitida" in rules(template("Runtime.getRuntime().exec(\"rm -rf /\").toString()")))
        assertTrue("expressao_nao_permitida" in rules(template("new ProcessBuilder(\"id\").start().toString()")))
    }

    @Test
    fun `deve recusar reflexao e carga de classe`() {
        assertTrue("expressao_nao_permitida" in rules(template("\"\".getClass().forName(\"java.lang.Runtime\").toString()")))
        assertTrue("expressao_nao_permitida" in rules(template("Class.forName(\"x\").toString()")))
    }

    @Test
    fun `deve recusar escape unicode, que o compilador Java processa ate dentro de texto`() {
        assertTrue("escape_unicode_proibido" in rules(template("\"\\u0022 + Runtime.getRuntime() + \\u0022\"")))
    }

    @Test
    fun `escape unicode escrito como entidade XML tambem e recusado`() {
        assertTrue("escape_unicode_proibido" in rules(template("\"&#92;u0022\"").replace("<![CDATA[", "").replace("]]>", "")))
    }

    @Test
    fun `deve recusar classe anonima, lambda, bloco e ponto e virgula`() {
        assertTrue("expressao_nao_permitida" in rules(template("new Object(){ public String toString(){ return \"x\"; } }.toString()")))
        assertTrue("expressao_nao_permitida" in rules(template("java.util.Objects.toString(() -> 1)")))
        assertTrue("expressao_nao_permitida" in rules(template("String::valueOf")))
    }

    @Test
    fun `deve recusar scriptlet, subreport, template externo e importacao`() {
        assertTrue("elemento_nao_permitido" in rules(template("\"x\"", "<scriptlet name=\"s\" class=\"java.lang.Object\"/>")))
        assertTrue("elemento_nao_permitido" in rules(template("\"x\"", "<import>java.io.File</import>")))
        assertTrue("elemento_nao_permitido" in rules(template("\"x\"", "<template><![CDATA[\"/etc/x.jrtx\"]]></template>")))
        val subreport = template("\"x\"").replace(
            "<element kind=\"textField\"",
            "<element kind=\"subreport\" x=\"0\" y=\"0\" width=\"1\" height=\"1\"/><element kind=\"textField\""
        )
        assertTrue("elemento_nao_permitido" in rules(subreport))
    }

    @Test
    fun `deve recusar scriptletClass e fonte por caminho de arquivo`() {
        assertTrue("atributo_nao_permitido" in rules(template("\"x\"").replace("language=\"java\"", "language=\"java\" scriptletClass=\"x.Y\"")))
        assertTrue("atributo_nao_permitido" in rules(template("\"x\"").replace("height=\"20\">\n", "height=\"20\" pdfFontName=\"/etc/passwd\">\n")))
    }

    @Test
    fun `deve recusar DOCTYPE e entidade externa`() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><jasperReport name=\"t\">&x;</jasperReport>"

        assertEquals(setOf("jrxml_dtd_proibido"), rules(xxe))
    }

    @Test
    fun `imagem so pode ser o logo`() {
        val image = """<element kind="image" x="0" y="0" width="10" height="10"><expression><![CDATA["file:/etc/passwd"]]></expression></element>"""
        val jrxml = template("\"x\"").replace("<element kind=\"textField\"", "$image<element kind=\"textField\"")

        assertTrue("imagem_nao_permitida" in rules(jrxml))
    }

    @Test
    fun `logo por URL externa e recusado`() {
        assertTrue("expressao_nao_permitida" in rules(template("new java.net.URL(\"http://interno/\").toString()")))
    }

    @Test
    fun `tipo declarado fora da lista e recusado`() {
        assertTrue("classe_nao_permitida" in rules(template("\"x\"", fieldClass = "java.lang.Runtime")))
    }

    @Test
    fun `propriedade de javascript no PDF e recusada`() {
        val jrxml = template("\"x\"", "<property name=\"net.sf.jasperreports.export.pdf.javascript\" value=\"app.alert(1)\"/>")

        assertTrue("propriedade_nao_permitida" in rules(jrxml))
    }

    @Test
    fun `recusa expressao que aloca memoria sem limite`() {
        assertTrue("expressao_nao_permitida" in rules(template("String.valueOf(1).repeat(1000000000)")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(\"%0999999999d\", 1)")))
        assertTrue("expressao_nao_permitida" in rules(template("new java.text.DecimalFormat(\"#\").format(new java.math.BigDecimal(\"1E999999999\"))")))
        assertTrue("expressao_nao_permitida" in rules(template("new java.math.BigDecimal(\"1\").setScale(999999999).toString()")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(\"%1\$1000000000s\", \"x\")")))
        assertTrue("expressao_nao_permitida" in rules(template("new java.math.BigDecimal(\"1E-999999999\").add(java.math.BigDecimal.ONE).toString()")))
    }

    @Test
    fun `recusa replace encadeado que multiplica o texto`() {
        val big = "y".repeat(500)
        val chained = "\"x\".replace(\"x\", \"$big\").replace(\"y\", \"$big\").replace(\"y\", \"$big\")"
        assertTrue("expressao_nao_permitida" in rules(template(chained)))
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace(\"x\", ${'$'}F{valor})")))
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace(\"x\", \"a\").replace(\"x\", \"a\").replace(\"x\", \"a\").replace(\"x\", \"a\").replace(\"x\", \"a\")")))
        assertTrue("expressao_nao_permitida" in rules(template("new java.math.BigDecimal(\"1\").divide(java.math.BigDecimal.TEN, 999999999, java.math.RoundingMode.HALF_UP).toString()")))
    }

    @Test
    fun `recusa comentario que esconde o replace e formato vindo de campo`() {
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace/**/(\"x\", ${'$'}F{valor})")))
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace(\"x\", \"y\") // nada")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(${'$'}F{valor}, \"x\")")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(String.valueOf(${'$'}F{valor}), \"x\")")))
    }

    @Test
    fun `formato montado a partir de campo ou concatenacao e recusado`() {
        assertTrue("expressao_nao_permitida" in rules(template("String.format(\"%\" + ${'$'}F{valor} + \"s\", \"x\")")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(\"%5\" + String.valueOf(${'$'}F{valor}), \"x\")")))
    }

    @Test
    fun `replace com texto novo acima de 2 caracteres ou mais de 3 chamadas e recusado`() {
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace(\"x\", \"abc\")")))
        assertTrue("expressao_nao_permitida" in rules(template("\"x\".replace(\"x\", \"a\").replace(\"a\", \"b\").replace(\"b\", \"c\").replace(\"c\", \"d\")")))
    }

    @Test
    fun `replace com busca vazia, concat e excesso de referencias a campo sao recusados`() {
        assertTrue("expressao_nao_permitida" in rules(template("String.valueOf(${'$'}F{valor}).replace(\"\", \"xx\")")))
        assertTrue("expressao_nao_permitida" in rules(template("${'$'}F{valor}.concat(${'$'}F{valor})")))
        val many = List(31) { "String.valueOf(${'$'}F{valor})" }.joinToString(" + ")
        assertTrue("expressao_nao_permitida" in rules(template(many)))
        val ok = List(18) { "String.valueOf(${'$'}F{valor})" }.joinToString(" + ")
        assertEquals(emptySet<String>(), rules(template(ok)))
    }

    @Test
    fun `String format com formato literal e divisao continuam aceitos`() {
        assertEquals(emptySet<String>(), rules(template("String.format(\"%,.2f\", ${'$'}F{valor}) + String.valueOf(10 / 4)")))
        assertEquals(emptySet<String>(), rules(template("String.format(new java.util.Locale(\"pt\", \"BR\"), \"%,.2f\", ${'$'}F{valor})")))
        assertTrue("expressao_nao_permitida" in rules(template("String.format(new java.util.Locale(\"pt\", \"BR\"), ${'$'}F{valor}, \"x\")")))
    }

    @Test
    fun `replace de separador, como nos templates reais, continua aceito`() {
        assertEquals(emptySet<String>(), rules(template("String.valueOf(${'$'}F{valor}).replace(\",\", \"X\").replace(\".\", \",\").replace(\"X\", \".\")")))
    }

    @Test
    fun `formato comum dos templates reais continua aceito`() {
        assertEquals(emptySet<String>(), rules(template("String.format(\"%,.2f\", \$F{valor})")))
    }

    @Test
    fun `lista os campos declarados`() {
        assertEquals(listOf("valor"), guard.fieldNames(template("\"x\"")))
    }

    @Test
    fun `erro de compilacao vira problema legivel`() {
        val (_, problems) = guard.compile(template("\$F{inexistente}"))

        assertEquals("jrxml_nao_compila", problems.single().regra)
    }
}
