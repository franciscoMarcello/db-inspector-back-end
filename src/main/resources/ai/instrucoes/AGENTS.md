# Relatorios do DB Inspector (AgroMobi) - instrucoes para agentes de IA

Voce cria relatorios no DB Inspector, o portal de relatorios sobre o banco PostgreSQL do AgroMobi
(ERP agro: agricola, confinamento, frota, estoque, fiscal, contabil, contratos), integrado ao SAP B1.
Estas instrucoes valem para qualquer agente: Claude, ChatGPT, Gemini, Copilot, Cursor ou script proprio.

Um relatorio e:
- `sql` - consulta PostgreSQL com parametros `:nome`;
- `variaveis` - os filtros da tela, um para cada `:nome`;
- `jrxml` (opcional) - layout do PDF em JasperReports;
- `sqlSap` + `chaveComparacao` (opcional) - consulta no SAP HANA para comparar com o AgroMobi.

**Voce cria e altera RASCUNHOS. Publicar e decisao de uma pessoa, na tela.** Ate alguem publicar,
quem so executa relatorios nao ve o rascunho, e a versao publicada continua valendo.

## Conexao

- URL base: `{{BASE_URL}}`
- Cabecalho em toda chamada: `Authorization: Bearer rpt_...` (token gerado na tela Relatorios > Tokens para IA)
- REST: endpoints abaixo, todos sob `{{BASE_URL}}/api/db/ai`. Contrato completo em `/api/db/ai/openapi.yaml`.
- MCP (Claude Code, Cursor, Codex, Gemini CLI...): `{{BASE_URL}}/api/db/ai/mcp`, transporte HTTP, mesmo cabecalho.
  As ferramentas MCP tem os mesmos nomes da coluna "Ferramenta".

| Ferramenta | REST | Para que |
|---|---|---|
| `ler_instrucoes` | `GET /instrucoes`, `GET /instrucoes/arquivo?caminho=...` | Este texto, glossario e exemplos |
| `listar_schemas` | `GET /catalogo/schemas` | Schemas liberados e quantas tabelas |
| `buscar_tabelas` | `GET /catalogo/tabelas?busca=colheita&schema=agricola` | Acha tabela por nome de tabela ou coluna |
| `descrever_tabela` | `GET /catalogo/tabelas/{schema}/{tabela}` | Colunas (conferidas ao vivo), PK, FKs nos dois sentidos, linhas estimadas |
| `consultar` | `POST /consultas` `{sql, limite}` | Testar hipotese. Nada e gravado |
| `explicar` | `POST /consultas/explicar` `{sql}` | Plano e custo estimado (EXPLAIN, sem executar) |
| `listar_pastas` | `GET /pastas` | Pastas onde o relatorio pode ficar |
| `listar_templates` / `obter_template` | `GET /templates`, `GET /templates/{id}` | JRXML existentes para reaproveitar |
| `listar_relatorios` / `obter_relatorio` | `GET /relatorios`, `GET /relatorios/{id}` | Relatorios existentes (definicao completa) |
| `listar_versoes` / `obter_versao` | `GET /relatorios/{id}/versoes[/{n}]` | Historico de versoes |
| `validar` | `POST /relatorios/validar` `{definicao, parametrosTeste}` | Valida tudo sem gravar |
| `criar_rascunho` | `POST /relatorios` `{definicao, nota, parametrosTeste}` | Relatorio novo, como rascunho |
| `salvar_rascunho` | `PUT /relatorios/{id}/rascunho` | Nova versao (rascunho) de um existente |
| `previa` | `POST /relatorios/{id}/versoes/{n}/previa` `{parametros}` | Roda a versao com dados reais (poucas linhas) |
| `previa_pdf` | `POST /relatorios/{id}/versoes/{n}/pdf/resumo` `{parametros}` | Gera o PDF; `.../pdf` devolve o arquivo |
| `solicitar_liberacao` / `consultar_liberacao` | `POST /liberacoes`, `GET /liberacoes/{id}` | Pedir para passar dos limites |

Voce **nao** publica, nao exclui, nao restaura versao, nao gerencia tokens, nao envia e-mail e nao
agenda. Esses caminhos respondem 403 para token de IA.

## O ciclo - sempre nesta ordem

1. **Entenda o pedido** e leia o glossario do dominio (`ler_instrucoes` com `glossario/README.md`).
   Veja se ja existe relatorio parecido (`listar_relatorios`) e o exemplo mais proximo (`exemplos/`).
2. **Descubra as tabelas**: `buscar_tabelas` -> `descrever_tabela`. Nao adivinhe nome de coluna.
3. **Teste as hipoteses** com `consultar` (roteiro abaixo). Anote os numeros.
4. **Valide** com `validar` ate `valido: true`. A resposta traz TODOS os problemas de uma vez:
   corrija todos antes de reenviar. Se a mesma `regra` voltar duas vezes seguidas, pare e pergunte
   a pessoa - nao tente variacoes as cegas.
5. **Grave o rascunho** (`criar_rascunho` ou `salvar_rascunho`) com uma `nota` para quem vai
   publicar: hipoteses testadas, numeros de conferencia, limitacoes conhecidas.
6. **Confira** com `previa` usando parametros reais, e `previa_pdf` se houver JRXML.
7. **Avise a pessoa**: "Relatorio #id, versao n, aguardando publicacao em Relatorios > Versoes".

Para alterar um relatorio existente: `obter_relatorio` (traz a definicao publicada completa,
inclusive o JRXML), mude o que precisa e use `salvar_rascunho`. Existe no maximo um rascunho ABERTO
por relatorio, mas cada gravacao e uma versao nova: salvar de novo cria a proxima versao e a anterior
vira historico (uma pessoa pode retomar qualquer uma pela tela). Nao e preciso poupar gravacoes.

## Teste suas hipoteses antes de criar

O banco nao tem documentacao: o que parece obvio no nome da coluna costuma estar errado. Antes de
gravar, confirme com `consultar` (prefira agregados - o retorno e limitado a {{QUERY_MAX_ROWS}} linhas):

1. **Status**: `SELECT status, count(*) FROM agricola.colheita GROUP BY status` antes de fixar um
   filtro. Status fixo no SQL some com registros sem ninguem perceber; prefira variavel com padrao.
2. **JOIN multiplica linha?** Conte antes e depois: `count(*)` e `count(DISTINCT c.id)` com e sem o JOIN.
3. **FK nula**: `count(*) FILTER (WHERE x_id IS NULL)` decide entre LEFT e INNER JOIN.
4. **Duplicidade de chave**: `GROUP BY chave HAVING count(*) > 1`.
5. **Total contra referencia**: confira a soma contra um relatorio existente, o SAP ou um numero que a
   pessoa conhece.
6. **Desempenho**: `explicar` numa faixa de datas curta e numa longa. Se a curta ficar mais lenta ou
   mais cara, o plano trocou (estimativa ruim), nao e volume. Veja `glossario/armadilhas.md`.
7. Escreva as conclusoes na `nota` do rascunho.

## Regras do SQL (o validador recusa o resto)

- Comeca com `SELECT` ou `WITH`. Uma instrucao so; nada de `;` no meio. **Sem comentarios** (`--`, `/* */`).
- Tabela sempre com schema: `agricola.colheita`, nunca `colheita`.
- Schemas liberados: {{SCHEMAS}}. {{TABELAS_EXTRAS}}
  Colunas com segredo, conta bancaria, contato e nascimento (`senha`, `password`, `token`, `email`,
  `phone`, `birthdate`, `conta_*`...) sao bloqueadas em qualquer tabela. `SELECT *` em tabela que tem
  uma delas e recusado: liste as colunas que precisa.
  O validador confere pelo plano do Postgres: view ou subconsulta que leia schema fora da lista tambem e recusada.
- Proibido: funcoes com efeito ou acesso externo (`nextval`, `setval`, `set_config`, `pg_sleep`,
  `pg_terminate_backend`, `pg_read_file`, `lo_*`, `dblink`, `query_to_xml` e afins), `SELECT ... INTO`,
  `FOR UPDATE/SHARE`, `U&'...'`.
- As palavras `insert update delete drop alter truncate create grant revoke merge copy call execute
  lock refresh` sao recusadas ate dentro de texto e alias. `updated_on`, `created_by_id` passam
  (sao outra palavra).
- Todo valor variavel vira `:parametro`. Nunca escreva valor de filtro fixo quando a pessoa pode
  querer mudar.

### Parametros e variaveis

```json
{ "chave": "fazenda", "rotulo": "Fazenda", "tipo": "string", "obrigatoria": false, "multipla": true,
  "padrao": null, "sqlOpcoes": "SELECT f.id AS valor, f.nome AS descricao FROM agro.fazenda f WHERE f.active ORDER BY f.nome" }
```

- `tipo`: `string`, `number`, `date` (`YYYY-MM-DD`), `datetime` (ISO-8601) ou `boolean`.
- Cada `:nome` do SQL precisa de uma variavel com `chave` igual.
- Variavel multipla: `col IN :nome` **sem parenteses** - o servidor gera `('8', '5')`.
- Filtro opcional: `AND (:fazenda IS NULL OR c.fazenda_id IN :fazenda)`.
- Filtro por id com varios valores: use `tipo: string`; o Postgres converte `'8'` para o bigint da coluna.
- `padrao` preenche a tela e vale quando a pessoa nao informa nada (ex.: status `E`).
- `sqlOpcoes` (lista da tela) devolve exatamente as colunas `valor` e `descricao`. Tambem passa pelo validador.
- Coluna timestamp filtrada por data: `c.dt::date >= :data_inicio`.
- Os aliases do SELECT sao o titulo da coluna na tela e o nome do campo no JRXML. Use `snake_case`
  ou aspas para titulo com espaco (`AS "Custo Total"`).

### Desempenho

- Agregue cada origem numa CTE **antes** do JOIN (operacao x despesa x colheita juntas viram produto cartesiano).
- `work_mem` do banco e baixo (4MB): sort e hash grandes vao para disco. Filtre cedo.
- Limite de custo estimado por consulta: {{MAX_COST}}. Acima disso, restrinja filtros ou peca liberacao.

## Comparacao com o SAP (opcional)

- `sqlSap` roda no SAP HANA. O schema e as tabelas vem dos exemplos de comparacao (se instalados) e da pessoa que
  pediu o relatorio. E validado so como texto (somente leitura, sem comentario).
- `chaveComparacao` precisa ser alias nos dois SQLs.
- **So colunas com o MESMO alias nos dois lados sao comparadas.** Alias diferente nunca e conferido
  e o resultado diz "iguais". Padronize os aliases (ex.: `quantidade_kg` dos dois lados).
- Se houver exemplo de comparacao em `exemplos/`, use-o como modelo.

## PDF (JRXML) - opcional

Sem JRXML o relatorio roda na tela e exporta Excel. Com JRXML, gera PDF.

- Reaproveite: `listar_templates`/`obter_template`, ou os `template.jrxml` dos exemplos (JasperReports 7,
  formato `<element kind="textField">` com `<expression>`; A4 paisagem 842x595; fonte padrao DejaVu Sans).
- `templateId` sozinho reaproveita um template sem mudar. Mandar `jrxml` diferente cria/atualiza o
  template deste relatorio ao publicar - nunca altera o de outros relatorios.
- Cada `<field name>` precisa ser um alias que o SQL devolve (o validador confere).
- Grupos do Jasper agrupam linhas **consecutivas**: o `ORDER BY` precisa comecar pelos campos dos grupos.
- Parametros que o PDF recebe: as `variaveis` (pela `chave`), `REPORT_NAME`, `LOGO_STREAM` e `FMT`.

Regras do validador de JRXML (expressoes Jasper viram codigo Java no servidor):
- `language="java"`; sem `<!DOCTYPE>`, scriptlet, `import`, subreport, `<template>`, hyperlink,
  `scriptletClass`, `pdfFontName`.
- Expressoes so com `$F{}`, `$P{}`, `$V{}`, literais, operadores, ternario e metodos simples de
  String/BigDecimal/Double/data. Sem `{ }`, `;`, lambda, `::` ou `\u`.
- Imagem so o logo: `<expression><![CDATA[$P{LOGO_STREAM}]]></expression>` com
  `<parameter name="LOGO_STREAM" class="java.io.InputStream"/>`.
- Tipos declarados (`class`): `java.lang.String`, `Integer`, `Long`, `Double`, `Boolean`, `Number`, `Object`,
  `java.math.BigDecimal`, `java.util.Date`, `java.sql.Timestamp`, `java.time.LocalDate`, `java.io.InputStream`.

Formatacao pt-BR pronta, em vez de `new java.text.DecimalFormat(...)` em cada campo:

```xml
<parameter name="FMT" class="com.chico.dbinspector.report.JasperFormat"/>
<expression><![CDATA[$P{FMT}.moeda($F{custo_total})]]></expression>   <!-- R$ 1.234,56 -->
```

`moeda(v)`, `numero(v)` ou `numero(v, casas)`, `inteiro(v)`, `percentual(v)` (12.5 -> `12,50%`),
`data(v)` (`dd/MM/yyyy`), `dataHora(v)`, `texto(v)`. Nulo vira texto vazio.

## Limites e liberacao

| | Limite |
|---|---|
| `consultar` | {{QUERY_MAX_ROWS}} linhas, {{PER_MINUTE}} por minuto, {{CONCURRENT}} por vez |
| `previa` | {{PREVIEW_ROWS}} linhas |
| `previa_pdf` | {{PDF_ROWS}} linhas |
| Custo estimado | {{MAX_COST}} |

Precisa de mais (ex.: ano inteiro para conferir um total)? `solicitar_liberacao` com o SQL exato,
o motivo e as linhas. Diga a pessoa que o pedido esta na tela (Relatorios > Pedidos da IA) e
acompanhe com `consultar_liberacao`. Aprovado, repita **o mesmo SQL** antes de `expiraEm`.
Nunca e liberado: escrita, schema fora da lista, funcao proibida.

## Erros

- `422 validacao_falhou`: `problemas[]` com `caminho` (onde), `regra` (codigo estavel), `mensagem` e `linha`.
- `429`: limite de frequencia ou consulta em andamento; `dica` diz o que fazer.
- `403`: acao reservada a pessoas, ou tabela fora da lista.
- `erro_no_banco`: a mensagem do Postgres (coluna inexistente, tipo errado...). Corrija e valide de novo.

## Material de apoio (`ler_instrucoes` com `caminho`)

- `glossario/README.md` - regras de negocio por dominio (status, custos, chaves SAP, armadilhas).
- `exemplos/<nome>/definicao.json` - relatorios reais no formato da API; `template.jrxml` quando tem PDF;
  `notas.md` quando ha estudo de caso. Ficam fora do repositorio (pasta de contexto), como o glossario.
  Exemplos disponiveis: {{EXEMPLOS}}.
