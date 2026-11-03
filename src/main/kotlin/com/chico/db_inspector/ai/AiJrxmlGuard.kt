package com.chico.dbinspector.ai

import com.chico.dbinspector.report.sanitizeJrxml
import net.sf.jasperreports.engine.JasperCompileManager
import net.sf.jasperreports.engine.JasperReport
import net.sf.jasperreports.engine.DefaultJasperReportsContext
import net.sf.jasperreports.engine.JRPropertiesUtil
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JRXML escrito por IA. As expressoes de um template Jasper viram codigo Java compilado e executado
 * no servidor; sem esta checagem, um template seria execucao remota de codigo.
 *
 * A regra e de lista PERMITIDA: cada identificador de cada expressao precisa estar na lista
 * (calibrada pelos templates reais de database/reports/template). Nada de bloco, ponto e virgula,
 * lambda, escape \u (o compilador Java o processa ate dentro de texto) ou imagem fora do logo.
 */
@Component
class AiJrxmlGuard {
    companion object {
        const val FORMAT_CLASS = "com.chico.dbinspector.report.JasperFormat"

        private val allowedIdentifiers = setOf(
            // palavras da linguagem
            "new", "null", "true", "false", "instanceof",
            // pacotes
            "java", "lang", "math", "text", "util", "sql", "time", "format",
            // classes
            "String", "Integer", "Long", "Double", "Float", "Short", "Boolean", "Number", "Object", "Math",
            "BigDecimal", "BigInteger", "RoundingMode", "DecimalFormat", "DecimalFormatSymbols", "NumberFormat",
            "SimpleDateFormat", "DateTimeFormatter", "Locale", "Date", "Timestamp", "LocalDate", "LocalDateTime", "Objects",
            // constantes
            "ZERO", "ONE", "TEN", "HALF_UP", "HALF_EVEN", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR",
            // metodos
            "valueOf", "toString", "trim", "strip", "replace", "toUpperCase", "toLowerCase", "substring", "length",
            "isEmpty", "isBlank", "contains", "startsWith", "endsWith", "equals", "equalsIgnoreCase", "compareTo",
            "indexOf", "lastIndexOf", "charAt", "parse", "doubleValue", "intValue", "longValue",
            "floatValue", "toBigInteger", "add", "subtract", "multiply", "divide", "negate", "abs", "max",
            // Sem repeat, setScale e toPlainString: com argumento grande alocam memoria sem limite.
            "min", "round", "signum", "getTime", "ofPattern", "of", "isNull", "nonNull",
            "requireNonNullElse", "getInstance", "getNumberInstance", "getCurrencyInstance", "join",
            // helper FMT
            "moeda", "numero", "inteiro", "percentual", "data", "dataHora", "texto"
        )

        private val allowedDeclaredClasses = setOf(
            "java.lang.Object", "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Number",
            "java.math.BigDecimal", "java.math.BigInteger", "java.util.Date", "java.sql.Date", "java.sql.Timestamp",
            "java.sql.Time", "java.time.LocalDate", "java.time.LocalDateTime", "java.io.InputStream", FORMAT_CLASS
        )

        private val forbiddenTags = setOf(
            "scriptlet", "import", "template", "subreport", "datasetRun", "connectionExpression",
            "dataSourceExpression", "parametersMapExpression", "returnValue", "subreportExpression"
        )
        private val forbiddenAttributes = setOf(
            "scriptletClass", "resourceBundle", "formatFactoryClass", "incrementerFactoryClass",
            "hyperlinkType", "hyperlinkTarget", "linkType", "linkTarget", "lazy", "isLazy",
            // o grafico carrega e instancia a classe indicada ao preencher o relatorio
            "customizerClass",
            // caminho de arquivo de fonte: o servidor leria o arquivo indicado
            "pdfFontName", "pdfEncoding"
        )
        private val forbiddenKinds = setOf("subreport", "generic", "component", "genericElement")

        private val stringLiteral = Regex("\"(\\\\.|[^\"\\\\])*\"")
        private val charLiteral = Regex("'(\\\\.|[^'\\\\])'")
        // O Jasper aceita espaco e acento no nome (alias do SQL) e o converte em identificador Java.
        private val placeholder = Regex("\\$[FPV]\\{[^}\\n\"\\\\]+}")
        private val numberLiteral = Regex("\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?[dDfFlL]?\\b")
        private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val unicodeEscape = Regex("\\\\u+[0-9a-fA-F]{4}")

        /** Largura/precisao de format com 3+ digitos ("%0999999999d") gera texto gigante. */
        private val wideFormat = Regex("%(\\d+\\$)?[-#+ 0,(<]*\\d{3,}|%(\\d+\\$)?[-#+ 0,(<]*\\d*\\.\\d{3,}")

        /** Numero em texto com expoente grande ("1E999999999") vira bilhoes de digitos ao formatar. */
        private val hugeExponent = Regex("\\d[eE][+-]?\\d{3,}")
        private const val MAX_LITERAL_LENGTH = 1_000

        // Limites de crescimento: replace e a unica operacao que multiplica o tamanho de um texto, entao
        // so e aceito com dois textos literais e o texto novo curto. O fator maximo e 2^3 = 8 vezes o
        // tamanho do texto de partida (que ja existe em memoria como dado ou literal).
        private const val MAX_EXPRESSION_LENGTH = 3_000
        private const val MAX_REPLACE_CALLS = 3
        // Cada referencia a campo pode copiar o dado: limita o total (os templates reais usam ate 18).
        private const val MAX_FIELD_REFERENCES = 30
        private val fieldReference = Regex("\\$[FPV]\\{")
        private const val MAX_REPLACEMENT_LENGTH = 2
        private val formatCall = Regex("\\bString\\s*\\.\\s*format\\s*\\(")
        // Aceita String.format("fmt", ...) e String.format(new Locale("pt", "BR"), "fmt", ...) (como os templates reais).
        private val formatWithLiteral = Regex(
            "\\bString\\s*\\.\\s*format\\s*\\(\\s*" +
                "(new\\s+(java\\s*\\.\\s*util\\s*\\.\\s*)?Locale\\s*\\(\\s*\"[^\"]*\"\\s*(,\\s*\"[^\"]*\"\\s*)?\\)\\s*,\\s*)?" +
                "\"(?:[^\"\\\\]|\\\\.)*\"\\s*[,)]"
        )
        private val replaceCall = Regex("\\.\\s*replace\\s*\\(")
        private val replaceWithLiterals =
            Regex("\\.\\s*replace\\s*\\(\\s*(\"(?:\\\\.|[^\"\\\\])+\"|'(?:\\\\.|[^'\\\\])')\\s*,\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|'(?:\\\\.|[^'\\\\])')\\s*\\)")
        private val bigNumber = Regex("(?<![A-Za-z_$])\\d{5,}")
    }

    /** Problemas de seguranca do JRXML, sem compilar nada. */
    fun problems(jrxml: String, caminho: String = "jrxml"): List<AiProblem> {
        val problems = mutableListOf<AiProblem>()
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(jrxml)) {
            return listOf(AiProblem(caminho, "jrxml_dtd_proibido", "Remova <!DOCTYPE> e <!ENTITY> do JRXML"))
        }
        val document = try {
            secureFactory().newDocumentBuilder().parse(InputSource(StringReader(jrxml)))
        } catch (ex: Exception) {
            return listOf(AiProblem(caminho, "jrxml_invalido", "XML invalido: ${ex.message?.take(300)}"))
        }
        val root = document.documentElement
        if (root.localName() != "jasperReport") {
            return listOf(AiProblem(caminho, "jrxml_invalido", "O elemento raiz deve ser <jasperReport>"))
        }
        root.getAttribute("language").takeIf { it.isNotBlank() && it != "java" }?.let {
            problems += AiProblem(caminho, "linguagem_nao_permitida", "Use language=\"java\" (veio '$it')")
        }

        val elements = generateSequence(listOf(root)) { level ->
            level.flatMap { it.childElements() }.takeIf { it.isNotEmpty() }
        }.flatten().toList()

        elements.forEach { element ->
            val tag = element.localName()
            if (tag in forbiddenTags || tag.contains("hyperlink", ignoreCase = true) || tag.contains("anchor", ignoreCase = true)) {
                problems += AiProblem(caminho, "elemento_nao_permitido", "Elemento <$tag> nao e permitido", lineOf(jrxml, "<$tag"))
            }
            val kind = element.getAttribute("kind")
            if (kind in forbiddenKinds) {
                problems += AiProblem(caminho, "elemento_nao_permitido", "Elemento kind=\"$kind\" nao e permitido", lineOf(jrxml, "kind=\"$kind\""))
            }
            forbiddenAttributes.filter { element.hasAttribute(it) }.forEach { attribute ->
                problems += AiProblem(caminho, "atributo_nao_permitido", "Atributo '$attribute' nao e permitido em <$tag>", lineOf(jrxml, attribute))
            }
            element.getAttribute("class").takeIf { it.isNotBlank() && it !in allowedDeclaredClasses }?.let { className ->
                problems += AiProblem(
                    caminho, "classe_nao_permitida",
                    "Tipo '$className' nao e permitido em <$tag>. Use tipos simples (String, BigDecimal, Double, Integer, Date...)",
                    lineOf(jrxml, className)
                )
            }
            if (tag == "property") {
                val name = element.getAttribute("name")
                val allowed = name.startsWith("com.jaspersoft.studio.") ||
                    name.startsWith("net.sf.jasperreports.json.field.") ||
                    name == "net.sf.jasperreports.default.locale"
                if (!allowed) {
                    problems += AiProblem(caminho, "propriedade_nao_permitida", "Propriedade '$name' nao e permitida", lineOf(jrxml, name))
                }
            }
            if (tag == "expression" || tag.endsWith("Expression")) {
                problems += expressionProblems(element.textContent.orEmpty(), caminho, jrxml)
            }
            if (kind == "image") {
                val expression = element.childElements().firstOrNull { it.localName() == "expression" }?.textContent.orEmpty()
                if (expression.replace(Regex("\\s+"), "") != "\$P{LOGO_STREAM}") {
                    problems += AiProblem(
                        caminho, "imagem_nao_permitida",
                        "Imagem so pode ser o logo: <expression><![CDATA[\$P{LOGO_STREAM}]]></expression>, " +
                            "com <parameter name=\"LOGO_STREAM\" class=\"java.io.InputStream\"/>",
                        lineOf(jrxml, "kind=\"image\"")
                    )
                }
            }
        }
        return problems.distinct()
    }

    /** Campos declarados (<field name>), para conferir contra as colunas do SQL. */
    fun fieldNames(jrxml: String): List<String> = runCatching {
        val document = secureFactory().newDocumentBuilder().parse(InputSource(StringReader(jrxml)))
        val fields = document.getElementsByTagName("field")
        (0 until fields.length).mapNotNull { (fields.item(it) as? Element)?.getAttribute("name")?.takeIf { name -> name.isNotBlank() } }
    }.getOrDefault(emptyList())

    /** Compila (sem preencher). So chamar depois de [problems] vazio. */
    fun compile(jrxml: String, caminho: String = "jrxml"): Pair<JasperReport?, List<AiProblem>> = try {
        val properties = JRPropertiesUtil.getInstance(DefaultJasperReportsContext.getInstance())
        properties.setProperty("net.sf.jasperreports.compiler.class", "net.sf.jasperreports.jdt.JRJdtCompiler")
        JasperCompileManager.compileReport(ByteArrayInputStream(sanitizeJrxml(jrxml).toByteArray(Charsets.UTF_8))) to emptyList()
    } catch (ex: Exception) {
        null to listOf(AiProblem(caminho, "jrxml_nao_compila", (ex.message ?: "Falha ao compilar o JRXML").take(1_500)))
    }

    private fun expressionProblems(expression: String, caminho: String, jrxml: String): List<AiProblem> {
        val line = lineOf(jrxml, expression.trim().take(40))
        if (unicodeEscape.containsMatchIn(expression)) {
            return listOf(AiProblem(caminho, "escape_unicode_proibido", "Nao use \\uXXXX em expressoes", line))
        }
        if (expression.length > MAX_EXPRESSION_LENGTH) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "Expressao com mais de $MAX_EXPRESSION_LENGTH caracteres", line))
        }
        // Comentario Java (/**/ no meio de "x".replace/**/(...)) escaparia dos regex que contam chamadas.
        if (expression.replace(stringLiteral, "\"\"").let { it.contains("/*") || it.contains("//") }) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "Comentarios (/* */ ou //) nao sao permitidos em expressao", line))
        }
        // O formato de String.format e o que define a largura: tem de ser um texto literal (ja limitado).
        if (formatCall.findAll(expression).count() != formatWithLiteral.findAll(expression).count()) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "String.format so aceita um texto literal como formato", line))
        }
        if (fieldReference.findAll(expression).count() > MAX_FIELD_REFERENCES) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "Mais de $MAX_FIELD_REFERENCES referencias a campo/parametro na mesma expressao", line))
        }
        val replaces = replaceCall.findAll(expression).count()
        if (replaces > MAX_REPLACE_CALLS) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "Mais de $MAX_REPLACE_CALLS chamadas a replace na mesma expressao", line))
        }
        val literalReplaces = replaceWithLiterals.findAll(expression).toList()
        if (literalReplaces.size != replaces) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "replace so aceita dois textos literais: replace(\"a\", \"b\")", line))
        }
        literalReplaces.forEach { match ->
            val replacement = match.groupValues[3].ifEmpty { match.groupValues[2].removeSurrounding("'") }
            if (replacement.length > MAX_REPLACEMENT_LENGTH) {
                return listOf(AiProblem(caminho, "expressao_nao_permitida", "Texto novo do replace com mais de $MAX_REPLACEMENT_LENGTH caracteres", line))
            }
        }
        stringLiteral.findAll(expression).map { it.value }.forEach { literal ->
            when {
                literal.length > MAX_LITERAL_LENGTH ->
                    return listOf(AiProblem(caminho, "expressao_nao_permitida", "Texto com mais de $MAX_LITERAL_LENGTH caracteres em expressao", line))
                wideFormat.containsMatchIn(literal) ->
                    return listOf(AiProblem(caminho, "expressao_nao_permitida", "Largura ou precisao de formato acima de 99 em $literal", line))
                hugeExponent.containsMatchIn(literal) ->
                    return listOf(AiProblem(caminho, "expressao_nao_permitida", "Numero com expoente grande demais em $literal", line))
            }
        }
        if (bigNumber.containsMatchIn(expression.replace(stringLiteral, "\"\""))) {
            return listOf(AiProblem(caminho, "expressao_nao_permitida", "Numero literal com mais de 4 digitos (escala ou tamanho enorme)", line))
        }
        val code = expression
            .replace(stringLiteral, "\"\"")
            .replace(charLiteral, "' '")
            .replace(placeholder, " __V__ ")
            .replace(numberLiteral, " 0 ")
        val problems = mutableListOf<AiProblem>()
        listOf("{", "}", ";", "@", "->", "::", "`", "$").filter { code.contains(it) }.forEach { token ->
            problems += AiProblem(caminho, "expressao_nao_permitida", "Expressao nao pode conter '$token': ${expression.trim().take(120)}", line)
        }
        identifier.findAll(code).map { it.value }.filter { it != "__V__" && it !in allowedIdentifiers }.distinct().forEach { name ->
            problems += AiProblem(
                caminho, "expressao_nao_permitida",
                "'$name' nao e permitido em expressao. Use \$P{FMT} (moeda, numero, inteiro, percentual, data, dataHora, texto) " +
                    "ou metodos simples de String/BigDecimal: ${expression.trim().take(120)}",
                line
            )
        }
        return problems
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    private fun Element.localName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun lineOf(text: String, needle: String): Int? =
        text.indexOf(needle).takeIf { needle.isNotBlank() && it >= 0 }?.let { index -> text.take(index).count { it == '\n' } + 1 }
}
