# Como conectar um agente de IA

Gere um token em Relatorios > Tokens para IA. O valor (`rpt_...`) aparece uma unica vez. Ele vale
ate expirar ou ser revogado e so cria rascunhos: publicar continua com uma pessoa.

URL base: `{{BASE_URL}}`

## Agentes com MCP (Claude Code, Codex, Cursor, Gemini CLI, VS Code/Copilot...)

Servidor MCP remoto, transporte HTTP, cabecalho fixo de autorizacao.

Claude Code:
```bash
claude mcp add --transport http dbinspector {{BASE_URL}}/api/db/ai/mcp \
  --header "Authorization: Bearer rpt_..."
```

Codex (OpenAI). O Codex nao aceita o token dentro do comando: ele le de uma variavel de ambiente.
```bash
export DBINSPECTOR_TOKEN="rpt_..."        # coloque tambem no perfil do shell (~/.zshrc ou ~/.bashrc)
codex mcp add dbinspector --url {{BASE_URL}}/api/db/ai/mcp --bearer-token-env-var DBINSPECTOR_TOKEN
```
Ou, no `~/.codex/config.toml`:
```toml
[mcp_servers.dbinspector]
url = "{{BASE_URL}}/api/db/ai/mcp"
bearer_token_env_var = "DBINSPECTOR_TOKEN"
```
Confira com `codex mcp list`. Abra o Codex a partir de um terminal que ja tenha a variavel definida.

Outros clientes (arquivo de configuracao MCP):
```json
{ "mcpServers": { "dbinspector": {
    "type": "http",
    "url": "{{BASE_URL}}/api/db/ai/mcp",
    "headers": { "Authorization": "Bearer rpt_..." } } } }
```

Ao conectar, o agente recebe as instrucoes e as ferramentas. Nada para copiar.

## ChatGPT (GPT personalizado com Actions)

1. Crie um GPT. Em **Instructions**, cole o conteudo de `AGENTS.md`.
2. Em **Knowledge**, envie `glossario/` e `exemplos/` deste pacote.
3. Em **Actions**, importe `openapi.yaml` deste pacote (ou a URL `{{BASE_URL}}/api/db/ai/openapi.yaml`).
4. Autenticacao: API Key, tipo Bearer, valor `rpt_...`.

## Qualquer outro agente (Gemini, API da OpenAI/Anthropic, LangChain, script)

- Use `openapi.yaml` como definicao das funcoes (function calling / tool use) e `AGENTS.md`
  como prompt de sistema.
- Toda chamada leva `Authorization: Bearer rpt_...`.
- Erros sao JSON legivel por maquina: `422` traz `problemas[]` com `regra`, `caminho`, `mensagem`.

## Sem conexao nenhuma (so conversa)

Cole `AGENTS.md` e o exemplo mais parecido na conversa. O agente escreve a definicao em JSON e uma
pessoa cria o relatorio pela tela. Nesse modo ele nao consegue testar hipoteses no banco.
