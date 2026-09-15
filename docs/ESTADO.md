# Estado del proyecto y siguiente sesión

Memoria de trabajo entre sesiones. Se actualiza al cerrar cada tramo de trabajo: qué hay, qué se ha verificado, qué decisiones se tomaron sobre la marcha y por dónde seguir. La arquitectura sigue en `docs/architecture/` y las decisiones cerradas en `docs/adr/`; esto es solo el diario.

## Dónde estamos (2026-09-15)

**Cerrada la v1.1: los comandos `/` y la compactación.** Era lo último que quedaba a medias de una versión
anterior, y el roadmap dice que no se empieza la siguiente con la anterior a medias. Antes, la v3 (frontend ACP
por stdio) y la v2 puntos 1 y 2 más la tool `fetch`. De v2 solo queda el proveedor `copilot`, que sigue
esperando a tener acceso para probarlo.

### Tramo 5 (v1.1, comandos `/` y compactación)

Rama `claude/v4-estados-implementation-g4hfjo`.

- `ContextManager.compact()`: el nivel 3 del doc 02, que hasta ahora no existía. Pide el resumen en una
  petición aparte (sin tools, con su propio system prompt) y deja el historial en `[resumen] + últimos 2
  turnos`. Devuelve `CompactResult` (`Done`/`NothingToDo`/`Failed`) además de emitir `Compacted`.
- `prepare()` compacta sola al pasar del 90% de la ventana; la poda sigue en el 75%.
- `frontend/Commands.kt`: `Commands.parse` convierte la línea en un `Command` y lo comparten la TUI y
  `--plain`. Los builtin ganan a un skill homónimo y un `/loquesea` desconocido se avisa en vez de mandárselo
  al modelo.
- `frontend/Clipboard.kt` (`/copy`): comando del sistema y, si no hay, OSC 52 —que funciona por ssh—. Sin AWT.
- `frontend/Editor.kt` (`/edit`): `$VISUAL` > `$EDITOR` > `vi`/`notepad`, sobre un temporal con `inheritIO`.
- `/config` enseña la configuración en vigor (fuentes, proveedor, modelo, ventana, reglas, truststore, proxy,
  skills, roles); `/reload` relee config, `AGENTS.md`, skills y roles sin tocar el historial.
- `/help` ya no promete nada para más adelante.

Lo que hizo falta tocar para que `/reload` no fuera mentira: `AgentContext.config` pasa a `var`,
`RulePolicy` separa las reglas de fichero de las de sesión (`replaceConfigRules` conserva las de "siempre en
esta sesión") y `TaskTool` recarga roles y `contextPrompt` con `description` como getter.

Decisiones del tramo:

- **El corte de la compactación cae siempre en un mensaje de usuario de verdad**, nunca en uno con
  `ToolResult`: cortar ahí dejaría resultados sin su `ToolUse` y el proveedor rechazaría la petición. Hay test.
- **Si el proveedor falla al resumir, el historial queda intacto** y se devuelve `Failed`. No se pierde
  contexto por un 502.
- **Tras compactar se olvida el `lastUsage`**: medía el historial viejo y, sin borrarlo, la ronda siguiente
  volvería a compactar. La medida real vuelve con la primera respuesta sobre el historial nuevo.
- **El `antes -> después` se mide con la misma vara en los dos lados** (caracteres). Comparar el `usage` de
  antes con una estimación de después daba números que no querían decir nada (se vio en la prueba con el jar).
- **`/config` solo enseña; `/reload` no cambia proveedor ni modelo.** El doc 08 los daba como "cambiar en
  caliente", pero el historial ya enviado va atado al proveedor que lo generó (ADR 0006). Si la config cambia
  el modelo, `/reload` lo dice y sigue con el de la sesión. Doc 08 reescrito para que diga lo que hace el código.

95 tests (23 nuevos: 7 de `ContextManagerTest`, 5 de `CommandsTest`, 5 de `LoopSessionTest`, 4 de `EditorTest`
y 2 de `ClipboardTest`). Verde tres veces seguidas.

**Probado contra el jar**, no solo en tests: `--plain` contra un servidor OpenAI-compatible falso, con
`/config`, `/help`, `/copy`, `/compact`, `/reload` y un `/nada` desconocido. Se comprobó en el volcado de
peticiones que la de resumen va sin `tools` y que la petición siguiente lleva `[resumen] + últimos 2 turnos`.
Ahí salieron los dos fallos que los tests no habían visto: un `describe()` que se llamaba a sí mismo
(`StackOverflowError`, el parámetro del constructor tapaba al método) y los números incomparables del evento
`Compacted`. Los dos tienen ahora test.

### Tramo 6 (primera corrida real: OpenRouter + TUI en terminal de verdad)

Carpeta `demo/`, con su propio README. kotycli manejado por `nvidia/nemotron-3.5-lightning:free` a
través de OpenRouter, escribiendo un ETL medallion en PySpark. Dos fallos encontrados y arreglados:

- **La TUI se comía los acentos sin `LANG` UTF-8.** `ensureUtf8Stdout()` solo cubre `System.out`; la
  TUI escribe por el writer de JLine, que desde JLine 3.25 usa `stdoutEncoding`, un ajuste distinto de
  `encoding`. Arreglado en `Tui.terminalBuilder()`, test en `TerminalEncodingTest`. **Es la primera vez
  que la TUI se ejecutaba fuera de un test**, con `script` para tener un PTY: el fallo apareció en el
  primer arranque.
- **Un turn que acaba sin texto no se distinguía de un cuelgue.** `TurnEnd` lleva ahora `answered` y los
  dos frontends lo avisan. Test en `RunLoopTest`.

Anotado y no arreglado:

- `read` sobre un directorio devuelve error; el modelo lo intentaba en casi cada arranque. Quizá
  debería listar el directorio en vez de fallar.
- Un 429 del proveedor termina el turn sin más. Con el tier gratuito (50 peticiones/día) pasa pronto;
  un reintento con backoff para 429 y 504 ahorraría tandas enteras.

98 tests. La demo quedó sin la capa gold: se agotó la cuota diaria de OpenRouter a mitad.

### Tramo 4 (v3, ACP)

Rama `claude/v3-continue-previous-state-fvn8gm`. Tres ficheros en `frontend/acp/` y un flag:

- `acp/JsonRpc.kt`: JSON-RPC 2.0 por líneas (un mensaje JSON por línea, sin `Content-Length`), en los dos
  sentidos: atiende las peticiones del cliente y manda las nuestras. Cada petición entrante se atiende en su
  propia corrutina, que es lo que permite que un `session/cancel` adelante a un `session/prompt` en curso.
- `acp/AcpWire.kt`: la traducción pura `AgentEvent` <-> wire, sin I/O ni estado, al estilo de `OpenAiWire`.
  Ahí están el `kind` por tool, el `diff` de `edit`/`create`, las rutas absolutas de `locations`, las tres
  opciones de permiso y los `stopReason`.
- `acp/Acp.kt`: las sesiones. Una por `session/new`, con el `cwd` que pida el cliente (se rebootstrapea, así
  que coge el `.kotycli/`, el `AGENTS.md` y los skills de ese directorio). Una bomba de eventos por sesión.
- `Main.kt`: `--acp`. Se queda con el descriptor real de stdout para el protocolo y redirige `System.out` a
  stderr, por si a alguna librería se le escapa un `println`. Los errores van a `~/.kotycli/logs/acp.log`.
- `AgentEvent.PermissionAsk` gana el `callId`: ACP necesita correlacionar el permiso con su `tool_call`, y el
  interceptor ya lo tenía a mano. Los otros dos frontends lo ignoran.
- `LoopSession` sale de `Main.kt` a `frontend/Frontend.kt`: era un objeto anónimo y ahora lo usan también los tests.
- `Render.toolSubject`/`toolTitle`: el título de una tool call se calculaba en la TUI y ahora lo comparten los tres.

Decisiones del mapeo que no estaban en el doc y ahora sí (doc 08 reescrito):

- Una tool call que espera permiso se anuncia primero como `tool_call` en `pending` y el `ToolStart` posterior
  solo manda el cambio a `in_progress`. Si se deniega no hay `ToolStart` ni `ToolEnd`, así que el propio `Deny`
  la cierra como `failed`; si no, el cliente se queda con una llamada colgada para siempre.
- Un `Failed` (error del proveedor) se devuelve como error JSON-RPC, no como un turn que acaba bien.
- La respuesta a `session/prompt` espera a que la bomba haya procesado el `TurnEnd`, para que ningún
  `session/update` llegue después de la respuesta que cierra el turn.
- Los subagentes van como `agent_thought_chunk`: ACP no los modela y el cliente decide si los agrupa.
- Los skills se anuncian como `available_commands_update` justo después de `session/new`, y un `/nombre` que
  llega en un `session/prompt` se expande con el mismo `SkillCatalog` que usan la TUI y `--plain`.
- Timeout de permiso de 5 min en el frontend, por delante de los 10 min del interceptor.

72 tests (20 nuevos: 10 de `AcpWireTest` sobre el mapeo puro, 9 de `AcpTest` de punta a punta contra un cliente
de mentira por pipes —permisos y cancelación incluidos— y uno más en `ArchitectureTest` que prohíbe `println` en
`frontend/acp/`). Verde tres veces seguidas.

**Probado también contra el jar de verdad**, no solo en tests: `java -jar kotycli.jar --acp` contra un servidor
OpenAI-compatible falso, con `initialize` -> `session/new` -> `session/prompt`, una `create` que pide permiso,
`session/request_permission` contestado por el cliente, el fichero escrito en disco, `stopReason: end_turn` y
salida limpia al cerrar stdin. Stdout solo llevó protocolo.

Sin probar: ningún cliente ACP real (agent-shell, Zed, Neovim) ha hablado todavía con esto. El wire está
verificado contra la especificación, no contra una implementación ajena.

### Tramos 1 a 3 (v2, ya en main)

Tramo 1 (`task` y subagentes):

- `agents/AgentType.kt` con los roles builtin `explorer` (solo lectura, con allowlist de `rg`, `git log`, `ls`… en `permissionRules`) e `implementor` (todo menos `task`).
- `agents/AgentTypeLoader.kt`: roles propios en `.agents/agents/*.md` y `~/.agents/agents/*.md`; gana proyecto > usuario > builtin.
- `skills/Frontmatter.kt`: el parser de frontmatter plano, compartido con los skills.
- `tools/TaskTool.kt`: otro `runLoop` con contexto virgen, mismo `Budget`, mismos interceptores, mismo `ToolEnv`; devuelve solo el último mensaje del hijo. Profundidad máxima 2 y `Semaphore` del padre.
- `Tool.parallel` (por defecto `readOnly`) para que `task`, que no es de solo lectura, se ejecute en paralelo con otras llamadas de la misma ronda. El dispatcher particiona por ahí.
- `AgentConfig.permissionRules`: reglas de permisos por agente, que `RulePolicy` suma a las globales. Formato `allow:bash(rg *)`.
- `SystemPrompt.context()`: el bloque de entorno + `AGENTS.md` se separa del prompt base para dárselo al hijo junto al prompt de su rol.
- Actualizados los docs 02, 03 y 04.

Tramo 2 (skills):

- `skills/SkillLoader.kt`: escanea `~/.agents/skills/` y los `.agents/skills/` del proyecto, desde la raíz del repo (la que tiene `.git`) hasta el directorio actual; gana el más cercano. Un skill sin `description` se ignora.
- `SkillCatalog.promptSection()` mete en el system prompt la lista `nombre — descripción — ruta`; el cuerpo lo lee el modelo con `read` cuando le hace falta, y se lee del disco en cada uso (editar un `SKILL.md` a mitad de sesión tiene efecto).
- `SkillCatalog.expand()` convierte `/nombre args` en un mensaje de usuario con el cuerpo del skill. Lo usan la TUI (con completer de JLine y listado en `/help`), `--plain` y ahora ACP.
- Los subagentes también ven la lista: va en `SystemPrompt.context()`.
- Sigue faltando `/reload` y los skills builtin embebidos en el jar.

Tramo 3 (tool `fetch`, lo que quedaba de v1.1):

- `tools/FetchTool.kt`: GET sobre el `HttpClient` común, solo `http(s)`, timeout de 30 s y corte a 512 KB. El HTML vuelve como markdown (jsoup, quitando `script`, `style`, `nav`, `header`, `footer`…); el resto tal cual, y `raw: true` desactiva la conversión. Con esto el toolset de `explorer` ya existe entero.

Además, arreglado un fallo de cancelación en `bash`: el lector de la salida era hijo del turn y un `read()` bloqueado no se interrumpe, así que si `ProcessHandle.descendants()` no ve a los nietos (pasa en contenedores sin `/proc` completo) el nieto mantenía el pipe abierto y Ctrl+C tardaba lo que tardase el comando. Ahora el lector va en su propio scope y se abandona al cancelar. Eso era el test flaky de `BashToolTest`.

Pendiente antes de fiarse: nadie ha visto todavía un subagente ni un skill contra un proveedor real, solo contra el `FakeProvider`. Para eso ya hay endpoint: [OpenRouter con `nvidia/nemotron-3.5-lightning:free`](#openrouter-con-nvidianemotron-35-lightningfree-probado), gratis y con `tools`, que da de sobra para pruebas básicas.

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
- **Un proveedor real: OpenRouter** (2026-09-14, ver más abajo). Deja de ser cierto que el harness no haya hablado nunca con un modelo de verdad.
- **El frontend ACP contra el jar**, con un cliente de mentira y un servidor OpenAI-compatible falso: turn completo con tool call, permiso y fichero escrito.
- **Los comandos `/` en `--plain` contra el jar** y la compactación de punta a punta (ver el tramo 5).

No verificado:

- **El resto de proveedores.** Ni Ollama en local, ni un gateway corporativo, ni Anthropic. OpenRouter no dice nada de esos tres.
- Windows: CI compila y pasa tests allí, pero nadie ha ejecutado la TUI en Windows Terminal ni el truststore `Windows-ROOT` con un proxy inspector de verdad.
- Cancelación con Ctrl+C en la TUI real (está implementada, no probada a mano). En ACP sí está probada: `session/cancel` tiene test.
- **Un cliente ACP de verdad.** El wire se ha escrito contra la especificación y probado contra un cliente propio; ni agent-shell, ni Zed, ni Neovim lo han ejecutado todavía. Es lo primero que hay que hacer en la siguiente sesión.
- **`/copy` y `/edit` a mano.** Los dos tienen test, pero en este contenedor no hay portapapeles ni `$EDITOR`:
  el único camino que se ha visto correr de verdad es el de OSC 52. Falta probarlos en un terminal con
  `wl-copy`/`pbcopy` y con un `$EDITOR` real, y en Windows Terminal.
- **La TUI ya no está sin ejecutar**: `/help`, `/config` y el arranque se han visto en un PTY real (tramo 6).
  Lo que sigue sin probarse a mano es Ctrl+C cancelando un turn, el completer con Tab y Windows Terminal.
- **La compactación contra un modelo de verdad.** El corte, el fallo y los números están testeados, pero la
  calidad del resumen solo se puede juzgar con un modelo real y una conversación larga.

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

## Siguiente sesión

1. **Abrir kotycli desde Emacs con agent-shell** (el criterio de éxito de la v3 en el roadmap) y arreglar lo que
   salga. Es lo único que falta para dar la v3 por buena: el protocolo está implementado y probado contra un
   cliente propio, pero eso no es lo mismo que contra uno ajeno. Con Zed y Neovim, igual. De paso, decidir si
   los builtin `/` se anuncian también por `available_commands_update`: hoy solo van los skills.
2. **Probar `/copy`, `/edit` y `/compact` a mano** en un terminal de verdad y con un modelo de verdad. Lo que
   no se ha podido ver correr en el contenedor.
3. **Proveedor `copilot`** (v2 punto 3, `06-proveedores.md`, ADR 0012): `OpenAiProvider` con un `Authenticator`
   que hace device flow, exchange de token y cabeceras `Editor-Version`/`Copilot-Integration-Id`. Token en
   `~/.kotycli/auth/github.json`. Tests contra fixtures, nunca contra la API real. Esperando a tener acceso.
4. **Lo de "después" del roadmap**: adaptador `anthropic` nativo (prompt caching y razonamiento, que la capa
   OpenAI-compatible no da), `--resume`, MCP cliente por stdio, `glob`/`grep` si la fricción lo justifica.

Cada tramo termina con tests y con esta nota actualizada.

## Cómo retomar

```
git fetch origin && git checkout claude/v4-estados-implementation-g4hfjo
./gradlew build
```

Leer `AGENTS.md` para las reglas del código y este fichero para el estado. Los ADR no se editan: si algo de v2 contradice uno, se escribe otro.
