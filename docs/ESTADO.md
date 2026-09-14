# Estado del proyecto y siguiente sesión

Memoria de trabajo entre sesiones. Se actualiza al cerrar cada tramo de trabajo: qué hay, qué se ha verificado, qué decisiones se tomaron sobre la marcha y por dónde seguir. La arquitectura sigue en `docs/architecture/` y las decisiones cerradas en `docs/adr/`; esto es solo el diario.

## Dónde estamos (2026-09-14)

**v2 en marcha: hechos el punto 1 (tool `task` y subagentes) y el punto 2 (skills)**, en la rama `claude/v2-implementation-continue-hf4wc6`. Lo de abajo (v1) ya está en `main`.

Tramo 2 (skills):

- `skills/SkillLoader.kt`: escanea `~/.agents/skills/` y los `.agents/skills/` del proyecto, desde la raíz del repo (la que tiene `.git`) hasta el directorio actual; gana el más cercano. Un skill sin `description` se ignora.
- `SkillCatalog.promptSection()` mete en el system prompt la lista `nombre — descripción — ruta`; el cuerpo lo lee el modelo con `read` cuando le hace falta, y se lee del disco en cada uso (editar un `SKILL.md` a mitad de sesión tiene efecto).
- `SkillCatalog.expand()` convierte `/nombre args` en un mensaje de usuario con el cuerpo del skill. Lo usan la TUI (con completer de JLine y listado en `/help`) y `--plain`.
- Los subagentes también ven la lista: va en `SystemPrompt.context()`.
- Sigue faltando `/reload` y los skills builtin embebidos en el jar.

Además, arreglado un fallo de cancelación en `bash`: el lector de la salida era hijo del turn y un `read()` bloqueado no se interrumpe, así que si `ProcessHandle.descendants()` no ve a los nietos (pasa en contenedores sin `/proc` completo) el nieto mantenía el pipe abierto y Ctrl+C tardaba lo que tardase el comando. Ahora el lector va en su propio scope y se abandona al cancelar. Eso era el test flaky de `BashToolTest`.

Tramo 1 (`task` y subagentes):

- `agents/AgentType.kt` con los roles builtin `explorer` (solo lectura, con allowlist de `rg`, `git log`, `ls`… en `permissionRules`) e `implementor` (todo menos `task`).
- `agents/AgentTypeLoader.kt`: roles propios en `.agents/agents/*.md` y `~/.agents/agents/*.md`; gana proyecto > usuario > builtin.
- `skills/Frontmatter.kt`: el parser de frontmatter plano, compartido con los skills.
- `tools/TaskTool.kt`: otro `runLoop` con contexto virgen, mismo `Budget`, mismos interceptores, mismo `ToolEnv`; devuelve solo el último mensaje del hijo. Profundidad máxima 2 y `Semaphore` del padre.
- `Tool.parallel` (por defecto `readOnly`) para que `task`, que no es de solo lectura, se ejecute en paralelo con otras llamadas de la misma ronda. El dispatcher particiona por ahí.
- `AgentConfig.permissionRules`: reglas de permisos por agente, que `RulePolicy` suma a las globales. Formato `allow:bash(rg *)`.
- `SystemPrompt.context()`: el bloque de entorno + `AGENTS.md` se separa del prompt base para dárselo al hijo junto al prompt de su rol.
- Actualizados los docs 02, 03 y 04.

46 tests en total (12 nuevos), verde cinco veces seguidas.

Pendiente antes de fiarse: nadie ha visto todavía un subagente ni un skill contra un proveedor real, solo contra el `FakeProvider`.

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

1. ~~**Tool `task` y subagentes**~~ hecho.
2. ~~**Skills**~~ hecho.
3. **Proveedor `copilot`** (`06-proveedores.md`, ADR 0012): `OpenAiProvider` con un `Authenticator` que hace device flow, exchange de token y cabeceras `Editor-Version`/`Copilot-Integration-Id`. Token en `~/.kotycli/auth/github.json`. Tests contra fixtures, nunca contra la API real.

Siguiente: la tool `fetch`, que conviene hacer pronto porque el toolset de `explorer` ya la nombra y todavía no existe, y después Copilot cuando haya acceso para probarlo. Cada tramo termina con tests y con esta nota actualizada.

## Cómo retomar

```
git fetch origin && git checkout claude/v2-implementation-continue-hf4wc6
./gradlew build
```

Leer `AGENTS.md` para las reglas del código y este fichero para el estado. Los ADR no se editan: si algo de v2 contradice uno, se escribe otro.
