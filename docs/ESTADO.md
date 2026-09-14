# Estado del proyecto y siguiente sesión

Memoria de trabajo entre sesiones. Se actualiza al cerrar cada tramo de trabajo: qué hay, qué se ha verificado, qué decisiones se tomaron sobre la marcha y por dónde seguir. La arquitectura sigue en `docs/architecture/` y las decisiones cerradas en `docs/adr/`; esto es solo el diario.

## Dónde estamos (2026-09-14)

**v2 en marcha: el punto 1 (tool `task` y subagentes) está hecho**, en la rama `claude/v2-implementation-continue-hf4wc6`. Lo de abajo (v1) ya está en `main`.

Lo que ha entrado en este tramo:

- `agents/AgentType.kt` con los roles builtin `explorer` (solo lectura, con allowlist de `rg`, `git log`, `ls`… en `permissionRules`) e `implementor` (todo menos `task`).
- `agents/AgentTypeLoader.kt`: roles propios en `.agents/agents/*.md` y `~/.agents/agents/*.md`; gana proyecto > usuario > builtin.
- `skills/Frontmatter.kt`: el parser de frontmatter plano, que reutilizarán los skills (punto 2).
- `tools/TaskTool.kt`: otro `runLoop` con contexto virgen, mismo `Budget`, mismos interceptores, mismo `ToolEnv`; devuelve solo el último mensaje del hijo. Profundidad máxima 2 y `Semaphore` del padre.
- `Tool.parallel` (por defecto `readOnly`) para que `task`, que no es de solo lectura, se ejecute en paralelo con otras llamadas de la misma ronda. El dispatcher particiona por ahí.
- `AgentConfig.permissionRules`: reglas de permisos por agente, que `RulePolicy` suma a las globales. Formato `allow:bash(rg *)`.
- `SystemPrompt.context()`: el bloque de entorno + `AGENTS.md` se separa del prompt base para dárselo al hijo junto al prompt de su rol.
- 8 tests nuevos (42 en total). Actualizados los docs 02, 03 y 04.

Pendiente antes de fiarse: nadie ha visto todavía un subagente contra un proveedor real, solo contra el `FakeProvider`.

**Ojo con un test flaky**: `BashToolTest.cancelar mata el proceso y no deja el script temporal` falla de vez en cuando en máquinas cargadas (espera cancelar en <10 s y a veces se come los 30 s del `sleep`). Se reproduce también sin estos cambios; está sin investigar.

## v1 (ya en main)

**v1 del roadmap implementada** (`docs/architecture/07-build-y-distribucion.md`, sección Roadmap) y mergeada a `main` en el PR #2.

Lo que hay:

- Proyecto Gradle de un módulo (Kotlin 2.4, JDK 21, Shadow, wrapper Gradle 9). `./gradlew build` genera `build/libs/kotycli.jar`.
- `core/`: modelo neutral de mensajes, `runLoop`, dispatcher con `readOnly` en paralelo, interceptores, `Budget`, `ContextManager` (solo poda de resultados viejos; la compactación no existe todavía), `AgentEvent`.
- `tools/`: `bash`, `read`, `edit`, `create`. Schema desde `@Serializable` + `@Description`. `Shell` detecta el shell y hace persistir el `cwd`.
- `interceptors/`: `PathGuard`, `Permissions` (modos `default`/`accept-edits`/`yolo`, reglas `tool(patrón)`, "siempre en esta sesión"), `ToolLog`, `Truncate`.
- `http/`: el único `HttpClient` con truststore combinado y proxy.
- `providers/`: interfaz `Provider`, adaptador `openai` (SSE, sin SDK), fábrica `Providers`.
- `config/`, `prompt/` (inyecta `AGENTS.md` global y del proyecto), `frontend/` (TUI append-only con JLine y `--plain`), `Main.kt` con clikt y `kotycli doctor`.
- 34 tests. `ArchitectureTest` vigila las reglas de dependencias entre paquetes.
- CI en GitHub Actions (Ubuntu y Windows).

Adelantado de v1.1 porque hacía falta para que v1 fuera usable con seguridad: `Permissions`, `ToolLog` y `--plain`.

## Qué se ha verificado y qué no

Verificado:

- Build limpio y tests en verde en Linux.
- Prueba end-to-end contra un servidor HTTP falso que habla el wire OpenAI por SSE: un turn con `bash` -> `read` -> `edit` encadenados, cada `ToolResult` vuelve como `role: tool`, el fichero cambia en disco.
- `kotycli doctor` contra ese servidor. `--plain` sin TTY resuelve los `Ask` como `Deny`. La TUI arranca sin TTY.

No verificado:

- **Ningún proveedor real.** Ni Ollama, ni un gateway corporativo, ni Anthropic. Es lo primero que hay que hacer antes de fiarse.
- Windows: CI compila y pasa tests allí, pero nadie ha ejecutado la TUI en Windows Terminal ni el truststore `Windows-ROOT` con un proxy inspector de verdad.
- Cancelación con Ctrl+C en la TUI real (está implementada, no probada a mano).

### Probar con la API de Anthropic sin escribir el adaptador nativo

Anthropic tiene un endpoint OpenAI-compatible oficial que encaja con el adaptador `openai` tal cual:

```json
{ "provider": "anthropic",
  "providers": { "anthropic": { "type": "openai", "baseUrl": "https://api.anthropic.com/v1",
                                "model": "claude-sonnet-5", "apiKeyEnv": "ANTHROPIC_API_KEY", "contextWindow": 1000000 } } }
```

Avisos: `kotycli doctor` hace `GET {baseUrl}/models`, que es la API nativa y puede no autenticar igual; probar con un prompt real aunque `doctor` falle ahí. Si la clave es de cuenta de servicio con varios workspaces hace falta la cabecera `anthropic-workspace-id`, y `ProviderConfig` no expone cabeceras extra todavía (`OpenAiProvider` sí las acepta en el constructor). Esa capa no soporta prompt caching ni expone el razonamiento; para eso está el adaptador nativo, que sigue siendo trabajo de v3.

## Decisiones tomadas sobre la marcha (no estaban en el borrador)

- Eventos `AgentEvent.Failed` (error del proveedor, el turn termina sin excepción) y `AgentEvent.TurnEnd`. Documentado en `02-agent-loop.md`.
- En las reglas de permisos `*` casa con cualquier cosa, barras incluidas. Documentado en `03-tools.md`.
- `main` fuerza stdout y stderr a UTF-8 si la JVM no lo trae; los launchers de `bin/` pasan además los flags.
- `Tool.execute` recibe un `ToolContext(agent, callId)` con `ok()`/`error()`; el doc 03 describía `ToolContext` con los campos de `ToolEnv`, que ahora vive en `AgentContext.env`.
- `ToolDefinition` está en `core/` porque la usan `tools/` y `providers/` y la regla es que `tools` no dependa de `providers`.
- Paquete raíz `com.softbenur.kotycli` (dominio del autor).
- `bash` ejecuta el script desde un fichero temporal, no como argumento de `-c`: Java en Windows no escapa las comillas dobles dentro de un argumento y `bash.exe` cortaba el script en la primera. Descubierto por el CI de Windows.

## Siguiente sesión: seguir la v2

Lo que queda de v1.1 es pequeño y se puede hacer al entrar en v2 o intercalado:

- Tool `fetch` (jsoup ya está en el catálogo, sin usar). `readOnly = true`, solo `http(s)`, usa `ToolEnv.http`.
- Comandos `/` en la TUI con completer de JLine: `/compact` (implica escribir la compactación en `ContextManager`, nivel 3), `/config`, `/copy`, `/edit` al `$EDITOR`, `/reload`.

v2 según el roadmap:

1. ~~**Tool `task` y subagentes**~~ hecho en este tramo.
2. **Skills** (`05-skills.md`): `SkillLoader` sobre `.agents/skills/` y `~/.agents/skills/`, parser de frontmatter plano, lista `nombre — descripción — ruta` en el system prompt, `/nombre args` en la TUI. Reutilizar el mismo parser para los roles de subagente.
3. **Proveedor `copilot`** (`06-proveedores.md`, ADR 0012): `OpenAiProvider` con un `Authenticator` que hace device flow, exchange de token y cabeceras `Editor-Version`/`Copilot-Integration-Id`. Token en `~/.kotycli/auth/github.json`. Tests contra fixtures, nunca contra la API real.

Siguiente: skills (el parser de frontmatter ya está, falta `SkillLoader`, la lista en el system prompt y `/nombre args` en la TUI) y después Copilot, cuando haya acceso para probarlo. La tool `fetch` conviene hacerla pronto: el toolset de `explorer` ya la nombra y todavía no existe. Cada tramo termina con tests y con esta nota actualizada.

## Cómo retomar

```
git fetch origin && git checkout claude/v2-implementation-continue-hf4wc6
./gradlew build
```

Leer `AGENTS.md` para las reglas del código y este fichero para el estado. Los ADR no se editan: si algo de v2 contradice uno, se escribe otro.
