# AGENTS.md — DB Inspector

Guia rapido para agentes OpenCode neste repositorio. Complementa `CLAUDE.md` com o que e facil errar na pratica.

## Comandos confiaveis (Gradle)

- Use sempre wrapper: `./gradlew` (Gradle 8.14.3).
- Build sem testes: `./gradlew assemble`.
- Suite completa: `./gradlew test`.
- Teste focado: `./gradlew test --tests "com.chico.dbinspector.util.ReadOnlySqlValidatorTest"`.
- Teste focado (outro exemplo): `./gradlew test --tests "com.chico.dbinspector.report.ReportServiceQueryTemplateTest"`.

## Estrutura real do backend

- Projeto unico (nao monorepo): `settings.gradle.kts` define apenas `rootProject.name = "db-inspector"`.
- Entrypoint: `src/main/kotlin/com/chico/db_inspector/DbInspectorApplication.kt`.
- Pacotes de dominio que concentram regras: `auth/`, `report/`, `email/`, `controller/`, `util/`.
- Convencao incomum: diretorio usa `db_inspector`, mas os `package` Kotlin sao `com.chico.dbinspector` (sem underscore). Nao “corrija” isso sem demanda explicita.

## Regras criticas que devem sobreviver a qualquer refactor

- SQL de usuario deve passar por `ReadOnlySqlValidator` antes de execucao; nao crie caminho alternativo.
- ACL/RBAC/JWT sao obrigatorios; verificacao de permissao fica no service (nao no controller).
- Nao exponha stack trace nem erro interno de banco em resposta HTTP.
- Login rate limit ja existe (`LoginRateLimiter` + propriedades `dbinspector.security.login-*`); nao enfraquecer.

## Configuracao e segredos

- Use `src/main/resources/application.properties.sample` como referencia de setup.
- `src/main/resources/application.properties` existe no repo com credenciais reais; trate como sensivel e nao replique valores em codigo, testes, logs ou docs.

## Testes e impacto de mudancas

- Framework atual: JUnit 5 + Mockito (nao MockK).
- Mudou validacao SQL ou montagem de query de relatorio? Rode no minimo:
  - `./gradlew test --tests "com.chico.dbinspector.util.ReadOnlySqlValidatorTest"`
  - `./gradlew test --tests "com.chico.dbinspector.report.ReportServiceQueryTemplateTest"`
- Mudou ACL/permissoes de relatorio? Rode `./gradlew test --tests "com.chico.dbinspector.report.ReportAccessControlServiceTest"`.
