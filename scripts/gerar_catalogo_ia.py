#!/usr/bin/env python3
"""Gera o catalogo do banco AgroMobi usado pelos agentes de IA.

Le os schema-context-*.json gerados por database/diagrams/diagramaAgrodog.js e grava um
catalogo compacto. O arquivo NAO deve ir para o git: grave-o na pasta de contexto
(dbinspector.ai.context-path).

- Os schemas public e br ficam fora, exceto as tabelas de cadastro listadas em EXTRA_TABLES
  (parceiro, empresa, cidade, UF, moeda; centro de custo, departamento, banco...). Usuarios,
  permissoes, logs, br.funcionario (senha_web) e br.endereco_pessoa nunca entram. Deve espelhar
  dbinspector.ai.allowed-tables. O schema br nao tem JSON em diagramaAgrodog.js: gere
  schema-context-br.json a partir do banco (ver LEIAME.md da pasta de contexto).
- O endpoint de origem (sql/exec) nao e copiado.
- Quais schemas/tabelas a IA enxerga de fato e decidido em runtime por dbinspector.ai.*.

Uso:
  python3 scripts/gerar_catalogo_ia.py <pasta-com-os-json> <pasta-de-contexto>/catalogo.json
"""
import glob
import json
import os
import sys

EXTRA_TABLES = {
    "public": {"res_partner", "res_company", "res_city", "res_country_state", "res_currency"},
    "br": {
        "centro_custo", "departamento", "banco", "cnae", "calendario", "calendario_feriado",
        "local_trabalho", "localidade", "mes", "orgao_emissor",
    },
}


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    source = sys.argv[1]
    target = sys.argv[2]

    tables = {}
    foreign_keys = []
    generated = []
    for path in sorted(glob.glob(os.path.join(source, "schema-context-*.json"))):
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        generated.append(data.get("gerado_em", ""))
        for table in data["tabelas"]:
            schema, name = table["schema"], table["nome"]
            if schema in EXTRA_TABLES and name not in EXTRA_TABLES[schema]:
                continue
            columns = sorted(table["colunas"], key=lambda c: c["posicao"])
            tables[(schema, name)] = {
                "schema": schema,
                "nome": name,
                "comentario": table.get("comentario"),
                "pk": [c["nome"] for c in columns if c["primary_key"]],
                "colunas": [
                    [c["nome"], c["tipo_completo"], bool(c["obrigatorio"])]
                    + ([c["comentario"]] if c.get("comentario") else [])
                    for c in columns
                ],
                "fks": [],
            }
        foreign_keys.extend(data["relacionamentos"])

    seen = set()
    for fk in foreign_keys:
        origin, dest = fk["origem"], fk["destino"]
        key = (origin["schema"], origin["tabela"], origin["coluna"], dest["schema"], dest["tabela"], dest["coluna"])
        table = tables.get((origin["schema"], origin["tabela"]))
        if table is None or key in seen:
            continue
        seen.add(key)
        table["fks"].append([origin["coluna"], dest["schema"], dest["tabela"], dest["coluna"]])

    catalog = {
        "geradoEm": max(generated) if generated else None,
        "tabelas": sorted(tables.values(), key=lambda t: (t["schema"], t["nome"])),
    }
    os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(catalog, handle, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(catalog['tabelas'])} tabelas, {len(seen)} FKs -> {os.path.abspath(target)}")


if __name__ == "__main__":
    main()
