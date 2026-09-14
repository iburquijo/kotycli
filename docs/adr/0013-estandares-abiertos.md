# ADR 0013. Estándares abiertos para todo lo que ve el modelo o comparte el usuario

**Estado:** aceptado

## Contexto

El proyecto nace de estar atado a herramientas propietarias. Sería absurdo que el harness propio creara su propio lock-in con ficheros y formatos inventados: un `KOTYCLI.md`, un formato de skill propio, un protocolo de editor propio. Cada uno de esos es un motivo para no poder cambiar de harness mañana, y para que lo que el equipo escribe para este harness no sirva en ningún otro.

## Decisión

Todo lo que ve el modelo, o que el usuario escribe y podría compartir, usa un estándar abierto ya adoptado por varias herramientas:

| Qué | Estándar | Dónde |
|-----|----------|-------|
| Instrucciones de proyecto | `AGENTS.md` (agents.md) | Raíz del repo; `~/.agents/AGENTS.md` para las globales del usuario |
| Skills | Agent Skills: carpeta con `SKILL.md` y frontmatter `name`/`description` | `.agents/skills/` y `~/.agents/skills/` |
| Roles de subagente | Mismo markdown con frontmatter que los skills | `.agents/agents/` y `~/.agents/agents/` |
| Integración con editores | ACP (JSON-RPC por stdio) | `--acp` |
| Wire de modelo | OpenAI-compatible `/v1/chat/completions` como mínimo común | adaptadores `openai` y `copilot` |
| Tools externas (después) | MCP | cliente por stdio |

Lo único con nombre propio es `.kotycli/`, que contiene configuración y estado del harness (`config.json`, `settings.json`, `auth/`, `logs/`, `sessions/`). Eso no lo lee el modelo ni lo comparte nadie.

Regla: si aparece en el diseño un fichero, formato o protocolo inventado aquí para algo que ya tiene un estándar, es un bug.

## Alternativas descartadas

- **Fichero de instrucciones propio (`KOTYCLI.md`)**: el equipo ya tiene `AGENTS.md` en sus repos. Inventar otro es duplicar y perder compatibilidad.
- **Extensiones propias al frontmatter de skills**: se aceptan solo campos opcionales que otros harnesses ignoren sin romperse; ninguno obligatorio.
- **Plugin de editor propio**: ver ADR 0010.

## Consecuencias

- Un `AGENTS.md` o un skill escrito para otra herramienta funciona aquí, y al revés.
- Si un estándar cambia, se sigue el estándar. No hay versión "kotycli" de nada.
- Cuando no exista estándar para algo (roles de subagente hoy), se usa el formato más cercano ya adoptado y se documenta como provisional.
