# ADR 0007. Skills en formato Agent Skills

**Estado:** aceptado

## Contexto

Queremos instrucciones reutilizables por tarea, cargadas solo cuando aplican para no gastar contexto. Ya existe un formato abierto y extendido: carpeta con `SKILL.md`, frontmatter con `name` y `description`.

## Decisión

- Adoptar ese formato sin extensiones propias obligatorias.
- Descripciones siempre en el system prompt; cuerpo bajo demanda vía tool `skill` o invocación explícita `/nombre`.
- Búsqueda en `.kotycli/skills/` (proyecto), `~/.kotycli/skills/` (usuario) y builtin embebidos, en ese orden de precedencia.
- Las definiciones de subagentes usan el mismo formato de markdown con frontmatter, para tener un solo parser.

## Alternativas descartadas

- **Formato propio (YAML, JSON)**: incompatible con los skills existentes, sin ventaja.
- **Cargar todos los skills en el system prompt**: no escala; con 20 skills se van miles de tokens en cada llamada.

## Consecuencias

- Un skill escrito para otro harness funciona aquí si no depende de tools que no tenemos.
- Necesitamos un parser de frontmatter YAML plano. Suficiente con `clave: valor`; no se soporta YAML completo salvo que aparezca la necesidad.
