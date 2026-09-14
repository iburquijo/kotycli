# Estado del proyecto y siguiente sesión

Memoria de trabajo entre sesiones. Se actualiza al cerrar cada tramo de trabajo: qué hay, qué se ha verificado, qué decisiones se tomaron sobre la marcha y por dónde seguir. La arquitectura sigue en `docs/architecture/` y las decisiones cerradas en `docs/adr/`; esto es solo el diario.

## Dónde estamos (2026-09-14)

**v1 del roadmap implementada** (`docs/architecture/07-build-y-distribucion.md`, sección Roadmap), en la rama `claude/kotycli-repo-structure-hfxqoh`, pendiente de merge a `main`.

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
- **Un proveedor real: OpenRouter** (2026-09-14, ver más abajo). Deja de ser cierto que el harness no haya hablado nunca con un modelo de verdad.

No verificado:

- **El resto de proveedores.** Ni Ollama en local, ni un gateway corporativo, ni Anthropic. OpenRouter no dice nada de esos tres.
- Windows: CI compila y pasa tests allí, pero nadie ha ejecutado la TUI en Windows Terminal ni el truststore `Windows-ROOT` con un proxy inspector de verdad.
- Cancelación con Ctrl+C en la TUI real (está implementada, no probada a mano).

### OpenRouter con `nvidia/nemotron-3.5-lightning:free` (probado)

El primer proveedor real contra el que ha corrido el agente. El adaptador `openai` vale tal cual:

```json
{ "provider": "openrouter",
  "providers": { "openrouter": { "type": "openai", "baseUrl": "https://openrouter.ai/api/v1",
                                 "model": "nvidia/nemotron-3.5-lightning:free", "apiKeyEnv": "OPENROUTER_TOKEN",
                                 "contextWindow": 1000000, "maxOutputTokens": 8192 } } }
```

Qué se comprobó: `doctor` devuelve 200 (ojo, `GET /models` de OpenRouter responde **sin autenticar**, así que un doctor verde no prueba que la clave sirva); un turn completo con `read` -> `create` encadenadas escribe el fichero correcto; los `tool_call_id` vuelven bien como `role: tool`; y `usage` llega con `prompt_tokens`/`completion_tokens`, o sea que el `Budget` cuenta. El modelo declara `tools` en `supported_parameters`, 1M de contexto y 65.536 de salida.

Avisos del wire de OpenRouter: manda keep-alives `: OPENROUTER PROCESSING` (los ignora `readSse`), y el razonamiento viaja en `reasoning`, no en `reasoning_content` (arreglado, ver abajo). El tier gratuito va justo de cuota y da algún `504 Upstream idle timeout` en horas de carga; reintentar basta.

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
- El razonamiento se guarda con el nombre que le da cada gateway (`reasoning_content` en DeepSeek y vLLM, `reasoning` en OpenRouter) y se devuelve con ese mismo nombre. Antes solo se miraba `reasoning_content` y con OpenRouter se perdía entero. No se guardan los `reasoning_details` estructurados: hoy ningún modelo probado los necesita para encadenar tool calls, y reconstruirlos desde los deltas es otro tramo. Medido: el eco del razonamiento son +93 tokens de entrada en una conversación de tres iteraciones, sin cambio apreciable de latencia.
- `bash` ejecuta el script desde un fichero temporal, no como argumento de `-c`: Java en Windows no escapa las comillas dobles dentro de un argumento y `bash.exe` cortaba el script en la primera. Descubierto por el CI de Windows.

## Deuda conocida

- `BashToolTest.cancelar mata el proceso y no deja el script temporal` **falla de forma intermitente en la suite completa** (2 de 3 pasadas), y pasa siempre aislado. También falla en `main` sin ningún cambio encima, con el síntoma exacto: la cancelación se come los 30s del `sleep` entero en vez de cortar a los 500ms. Con la máquina cargada, la cancelación no llega a matar el proceso. O el test es frágil o la cancelación de `bash` tiene una carrera de verdad; hay que mirarlo antes de fiarse del Ctrl+C.

## Siguiente sesión: empezar la v2

Lo que queda de v1.1 es pequeño y se puede hacer al entrar en v2 o intercalado:

- Tool `fetch` (jsoup ya está en el catálogo, sin usar). `readOnly = true`, solo `http(s)`, usa `ToolEnv.http`.
- Comandos `/` en la TUI con completer de JLine: `/compact` (implica escribir la compactación en `ContextManager`, nivel 3), `/config`, `/copy`, `/edit` al `$EDITOR`, `/reload`.

v2 según el roadmap:

1. **Tool `task` y subagentes** (`04-subagentes.md`): `AgentType`, roles builtin `explorer` e `implementor`, `ToolRegistry.restrictedTo`, mismo `Budget`, `Semaphore` (ya está en `AgentContext.subagentSemaphore`), profundidad 2, eventos `SubagentStart`/`SubagentEnd` (ya existen y los frontends ya los pintan). Roles propios en `.agents/agents/*.md`.
2. **Skills** (`05-skills.md`): `SkillLoader` sobre `.agents/skills/` y `~/.agents/skills/`, parser de frontmatter plano, lista `nombre — descripción — ruta` en el system prompt, `/nombre args` en la TUI. Reutilizar el mismo parser para los roles de subagente.
3. **Proveedor `copilot`** (`06-proveedores.md`, ADR 0012): `OpenAiProvider` con un `Authenticator` que hace device flow, exchange de token y cabeceras `Editor-Version`/`Copilot-Integration-Id`. Token en `~/.kotycli/auth/github.json`. Tests contra fixtures, nunca contra la API real.

Orden sugerido: `task` primero (es lo que más valor da y ya tiene medio cableado en el core), skills después, Copilot cuando haya acceso para probarlo. Cada tramo termina con tests y con esta nota actualizada.

## Cómo retomar

```
git fetch origin && git checkout claude/kotycli-repo-structure-hfxqoh   # o main si ya está mergeado
./gradlew build
```

Leer `AGENTS.md` para las reglas del código y este fichero para el estado. Los ADR no se editan: si algo de v2 contradice uno, se escribe otro.
