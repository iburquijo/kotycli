# ADR 0007. Skills en formato Agent Skills, sin tool dedicada

**Estado:** aceptado

## Contexto

Queremos instrucciones reutilizables por tarea, cargadas solo cuando aplican para no gastar contexto. Ya existe un formato abierto y extendido: carpeta con `SKILL.md`, frontmatter con `name` y `description`.

## Decisión

- Adoptar ese formato sin extensiones propias obligatorias.
- Descripciones y rutas siempre en el system prompt; cuerpo bajo demanda **leyéndolo con `read`**. No hay tool `skill`: seguimos en seis tools (ADR 0003) y el modelo ya sabe leer ficheros.
- Invocación explícita `/nombre argumentos` desde el frontend.
- Búsqueda en `.agents/skills/` (proyecto), `~/.agents/skills/` (usuario) y builtin embebidos, en ese orden de precedencia.
- Las definiciones de roles de subagente usan el mismo markdown con frontmatter, para tener un solo parser.
- Instrucciones siempre presentes van en `AGENTS.md` (estándar agents.md), no en un skill ni en un fichero propio.

## Alternativas descartadas

- **Formato propio (YAML, JSON)**: incompatible con los skills existentes, sin ventaja.
- **Cargar todos los skills en el system prompt**: no escala; con 20 skills se van miles de tokens en cada llamada.
- **Tool `skill` dedicada**: una tool más por algo que `read` ya hace. La única ventaja sería un evento de UI específico, y no compensa.

## Consecuencias

- Un skill escrito para otro harness funciona aquí si no depende de tools que no tenemos.
- Necesitamos un parser de frontmatter YAML plano. Suficiente con `clave: valor`.
