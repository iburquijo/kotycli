# 07. Build, dependencias y distribución

## Toolchain

- **JDK 21** (LTS). Lo que hay en cualquier máquina corporativa, Windows incluido.
- **Kotlin 2.x**, Gradle con Kotlin DSL, wrapper commiteado.
- **Shadow plugin** (`com.gradleup.shadow`) para el fat jar. `./gradlew shadowJar` produce `build/libs/kotycli.jar` con `Main-Class` en el manifest.
- Arranque: `java -jar kotycli.jar`, más un `kotycli.cmd` y un `kotycli` de una línea para el PATH. Si el arranque de la JVM molesta, AppCDS primero y GraalVM native-image después, pero no es prioridad.

## Dependencias

Pocas y aburridas:

| Necesidad | Librería | Por qué |
|-----------|----------|---------|
| Concurrencia y cancelación | `kotlinx-coroutines-core` | Todo el loop es `suspend`; cancelación estructurada gratis |
| JSON y schemas de tools | `kotlinx-serialization-json` | Sin reflexión; el schema se genera del `SerialDescriptor` |
| HTTP | `java.net.http.HttpClient` | En el JDK; truststore y proxy se configuran en un sitio |
| Args de CLI | `clikt` | Subcomandos, flags, help autogenerado |
| Prompt, historial, completado | `jline3` | Modo interactivo. Comandos `/` con su completer. Funciona en Windows Terminal |
| HTML a markdown en `fetch` | `jsoup` | Maduro, sin dependencias transitivas |
| YAML de frontmatter | parser propio mínimo | El frontmatter es `clave: valor` plano |
| Tests | JUnit 5 + `kotlinx-coroutines-test` | Estándar |
| Logs | `slf4j` + `logback` a fichero (`~/.kotycli/logs/`) | Nunca a stdout, que es del frontend (y en ACP, del protocolo) |

Cosas que **no** entran: Spring, frameworks de DI, Jackson, frameworks de TUI de pantalla completa, librerías de "agentes" que impongan su propio loop, SDKs de proveedor salvo que el adaptador `anthropic` lo justifique.

## Layout de paquetes (un solo módulo)

```
src/main/kotlin/dev/kotycli/
  Main.kt                      # clikt: `kotycli [prompt]`, `--plain`, `--acp`, `--provider`, `--mode`
  core/
    Message.kt                 # modelo neutral: Message, Block, StopReason, Completion
    AgentContext.kt
    RunLoop.kt
    Dispatcher.kt              # dispatch + cadena de interceptores
    ToolInterceptor.kt
    ContextManager.kt
    AgentEvent.kt
    Budget.kt
  tools/
    Tool.kt                    # interfaz, ToolRegistry, ToolContext, FileTracker, @Description, SchemaGen
    BashTool.kt
    ReadTool.kt
    EditTool.kt
    CreateTool.kt
    FetchTool.kt
    TaskTool.kt
  interceptors/
    PathGuard.kt
    Permissions.kt             # PermissionPolicy, modos, parser de reglas "bash(git status*)"
    ToolLog.kt
    Truncate.kt
  skills/
    SkillLoader.kt
    Frontmatter.kt
  agents/
    AgentType.kt
    AgentTypeLoader.kt
  providers/
    Provider.kt                # interfaz, Request, Capabilities, StreamEvent
    openai/OpenAiProvider.kt   # wire /chat/completions + SSE
    copilot/CopilotAuth.kt     # device flow, token exchange, headers
    anthropic/                 # opcional, después
  http/
    Http.kt                    # el único HttpClient: truststore, proxy, timeouts
  config/
    Config.kt                  # carga ~/.kotycli + .kotycli, merge, env
  frontend/
    tui/Tui.kt                 # append-only, JLine, comandos /, pager
    plain/Plain.kt             # sin ANSI, sin raw mode
    acp/Acp.kt                 # JSON-RPC por stdio
  prompt/
    SystemPrompt.kt            # ensambla: base + KOTYCLI.md + lista de skills + entorno (shell, OS, cwd)
```

Regla de dependencias entre paquetes (se verifica en CI):

```
frontend/* -> core, config
core -> providers (solo la interfaz), tools (solo la interfaz)
tools, interceptors -> core, http
providers/<x> -> providers (interfaz), core.Message, http
nadie -> frontend
nadie fuera de providers/<x> -> tipos de wire de <x>
```

## Ficheros en disco

```
~/.kotycli/
  config.json
  settings.json          # reglas de permisos persistidas
  auth/github.json       # token de device flow (solo Copilot)
  KOTYCLI.md
  skills/
  agents/
  logs/                  # app.log, tools.jsonl
  sessions/<id>.jsonl    # historial serializado, para --resume (después)

<repo>/.kotycli/
  config.json            # override por proyecto
  settings.json
  skills/
  agents/
<repo>/KOTYCLI.md
```

## Roadmap

| Versión | Contenido | Criterio de éxito |
|---------|-----------|-------------------|
| **v1** | Loop + 4 tools (`bash`, `read`, `edit`, `create`), TUI append-only pelada, proveedor `openai` contra Ollama o un gateway. Sin subagentes, sin `/`, sin pager. `HttpClient` con truststore y proxy desde el día uno. | Resolver un refactor real en un repo propio |
| **v1.1** | `fetch`, comandos `/` con JLine (`/compact`, `/config`, `/copy`, `/edit`), `/edit` al `$EDITOR`, interceptores `Permissions` y `ToolLog`, `--plain`. | Usarlo a diario en el trabajo |
| **v2** | Proveedor `copilot` (device flow, exchange, headers). Tool `task` con roles `explorer` e `implementor`, presupuesto compartido, cancelación en cascada. Skills y `KOTYCLI.md`. Pager vim si apetece. | Exploraciones grandes sin reventar el contexto |
| **v3** | Frontend ACP por stdio: `Flow<AgentEvent>` mapeado a `session/update` y `PermissionAsk` a `session/request_permission`. | Trabajar desde Emacs con agent-shell sin escribir elisp. Gratis: Zed y Neovim |
| después | Adaptador `anthropic`, `--resume`, MCP cliente por stdio, `glob`/`grep` si la fricción lo justifica, AppCDS o native-image. | |

Cada versión termina con algo usable. No se empieza la siguiente con la anterior a medias.
