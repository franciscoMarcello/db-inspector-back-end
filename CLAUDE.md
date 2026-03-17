# CLAUDE.md — DB Inspector (Backend)

## Sobre o projeto

DB Inspector é um portal de BI interno. Executa queries SQL somente-leitura contra PostgreSQL, gerencia relatórios parametrizados, gera PDFs (Jasper), exporta Excel (POI), agenda envios por email (Quartz) e controla acesso via JWT + RBAC com ACLs por recurso.

## Stack

- Kotlin 1.9 / JDK 21
- Spring Boot 3.5
- PostgreSQL
- Quartz Scheduler
- JasperReports 7
- Apache POI
- Gradle (verifique antes de qualquer acao)

## Regras gerais

- Sem emojis em codigo, comentarios ou commits.
- Comentarios somente quando o codigo nao for autoexplicativo.
- Respeite a estrutura de pacotes existente: `auth/`, `report/`, `email/`, `controller/`, `util/`. Nao crie pacotes novos sem necessidade real.
- Nao crie arquivos que nao foram solicitados.
- Antes de criar qualquer classe, analise o padrao ja existente no pacote alvo e replique.
- Respostas e explicacoes em portugues BR.

## Regras de seguranca (criticas)

- Toda query SQL passa por validacao read-only antes de execucao. Nunca contorne essa validacao.
- Nao introduza caminhos que permitam INSERT, UPDATE, DELETE, DROP, TRUNCATE ou qualquer operacao destrutiva.
- Nunca exponha stack traces ou mensagens internas de erro do banco ao usuario final.
- JWT e RBAC sao obrigatorios em todos os endpoints. Nenhum endpoint novo sem autenticacao/autorizacao.
- ACLs por recurso (relatorio/pasta) devem ser respeitadas. Verificar permissao no service, nao no controller.
- As 13 permissoes existentes cobrem os casos atuais. Nao crie permissoes novas sem alinhamento.
- Rate limiting de login ja existe. Nao remova ou enfraqueça.

## Codigo Kotlin

- Kotlin idiomatico. Nao escreva Java disfarçado de Kotlin.
- `data class` para DTOs.
- `val` por padrao. `var` somente quando mutabilidade for inevitavel.
- Null safety nativo. Nao use `!!` — trate com `?.`, `?:` ou `let`.
- Extension functions quando melhorar legibilidade, sem exagero.
- Nao use `@Autowired` em campo. Constructor injection via `val` no construtor primario.

## Spring Boot

- Controllers finos. Logica de negocio no Service.
- DTOs separados de entidades. Request DTO e Response DTO.
- Validacao com Bean Validation (`@Valid`, `@NotBlank`, etc).
- Exception handling centralizado com `@RestControllerAdvice`.
- Nao exponha entidades JPA nos endpoints.
- Status codes HTTP corretos. 403 para permissao negada, 404 para recurso inexistente, 400 para input invalido.

## Queries SQL e relatorios

- Toda query submetida pelo usuario e somente-leitura. A validacao em `util/` e a linha de defesa — nao crie atalhos.
- Parametros de relatorio usam a convencao `:varName`. Mantenha esse padrao.
- Queries com CTE sao permitidas. Subqueries tambem. Mas tudo passa pela validacao.
- Ao mexer na execucao de queries, considere SQL injection. Use prepared statements/named parameters sempre.
- Nunca concatene input do usuario direto na string SQL.

## JasperReports

- Templates Jasper (.jrxml) devem suportar Unicode.
- Nao altere templates existentes sem entender o layout atual. Jasper e fragil com mudancas cegas.
- Se criar template novo, siga o padrao dos existentes (fontes, margens, encoding).

## Apache POI (Excel)

- Fechamento de Workbook e streams e obrigatorio. Use `use {}` ou try-finally.
- Para volumes grandes, considere SXSSFWorkbook (streaming) ao inves de XSSFWorkbook.

## Quartz Scheduler

- Cron expressions sao definidas pelo usuario. Valide antes de registrar no scheduler.
- Jobs de envio de email devem ser idempotentes — se executar duas vezes, nao duplica envio.
- Nao altere a configuracao global do Quartz sem necessidade explicita.

## Testes

- Todo service novo precisa de teste unitario.
- Todo endpoint novo precisa de teste de integracao.
- Testes de validacao SQL sao criticos — qualquer mudanca no validador exige testes que cubram tentativas de bypass (DROP disfarçado em CTE, comentarios SQL maliciosos, etc).
- Use o framework de testes ja presente no projeto (JUnit 5 + MockK ou Mockito — verifique antes).
- Nomenclatura: descreva o cenario. Exemplo: `deve bloquear query com DELETE dentro de CTE`.
- Rode os testes antes de finalizar: `./gradlew test`.

## Commits

- Mensagens em portugues.
- Formato: `tipo: descricao curta`
- Tipos: `feat`, `fix`, `refactor`, `test`, `chore`, `docs`
- Exemplo: `fix: corrige validacao de query com comentario SQL inline`

## O que NAO fazer

- Nao instale dependencias novas sem perguntar antes.
- Nao altere application.yml, build.gradle ou configuracoes de seguranca sem necessidade explicita.
- Nao crie camadas de abstracao desnecessarias.
- Nao adicione logging excessivo. Log relevante sim, println jamais.
- Nao crie classes "Utils" ou "Helper" genericas. Use extension functions no contexto correto.
- Nao mexa na logica de ACL sem entender o modelo de permissoes completo.
- Nao altere a validacao read-only de SQL sem cobertura de testes extensiva.