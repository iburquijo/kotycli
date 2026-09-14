# 07. Build, dependencias y distribución

## Toolchain

- **JDK 21** (LTS). Lo que hay en cualquier máquina corporativa.
- **Kotlin 2.x**, Gradle con Kotlin DSL, wrapper commiteado.
- **Shadow plugin** (`com.gradleup.shadow`) para el fat jar. Un `./gradlew shadowJar` y sale `build/libs/kotycli.jar` con `Main-Class` en el manifest.
- Script de arranque de una línea: `alias kotycli='java -jar ~/bin/kotycli.jar'`. Más adelante, si molesta el arranque de la JVM, se evalúan AppCDS o GraalVM native-image, pero no es prioridad.

## Dependencias

Pocas y aburridas:

| Necesidad | Librería | Por qué |
|-----------|----------|---------|
| Concurrencia y cancelación | `kotlinx-coroutines-core` | Todo el loop es `suspend`; cancelación estructurada gratis |
| JSON | `kotlinx-serialization-json` | Sin reflexión, funciona bien en fat jar, `JsonObject` como tipo de input de tools |
| Args de CLI | `clikt` | Subcomandos, flags, help autogenerado |
| Salida en terminal | `mordant` | Colores, markdown básico, spinners; multiplataforma |
| Línea de entrada | `jline3` | Historial, edición de línea, multiline. Solo en el modo interactivo |
| Proveedor Anthropic | `anthropic-java` | SDK oficial |
| Proveedor OpenAI-compatible | `java.net.http.HttpClient` | Sin dependencia extra |
| YAML de frontmatter | parser propio mínimo o `kaml` | El frontmatter es `clave: valor` plano; se decide al implementar |
| Tests | JUnit 5 + `kotlinx-coroutines-test` + `mockk` | Estándar |
| Logs | `slf4j` + `logback` a fichero (`~/.kotycli/logs/`) | Nunca a stdout, que es de la UI |

Cosas que **no** entran: Spring, frameworks de DI, Jackson (con serialization sobra), librerías de "agentes" que impongan su propio loop.

## Layout de paquetes (un solo módulo)

```
src/main/kotlin/dev/kotycli/
  Main.kt                      # clikt: `kotycli [prompt]`, `--print`, `--provider`, `--mode`
  core/
    Message.kt                 # modelo neutral: Message, Block, StopReason, Completion
    Session.kt
    AgentLoop.kt
    ToolDispatcher.kt
    ContextManager.kt
    AgentEvent.kt
    Budget.kt
  tools/
    Tool.kt                    # interfaz + ToolRegistry + ToolContext + FileTracker
    BashTool.kt
    ReadTool.kt
    WriteTool.kt
    EditTool.kt
    GlobTool.kt
    GrepTool.kt
    AgentTool.kt
    SkillTool.kt
  permissions/
    PermissionPolicy.kt
    Rules.kt                   # parser de "bash(git status*)" etc.
  skills/
    SkillLoader.kt
    Frontmatter.kt
  agents/
    AgentDefinition.kt
    AgentLoader.kt
  providers/
    LlmProvider.kt             # interfaz, Request, Capabilities, StreamEvent
    anthropic/AnthropicProvider.kt
    openai/OpenAiCompatProvider.kt
  config/
    Config.kt                  # carga ~/.kotycli + .kotycli, merge, env
  ui/
    Tui.kt                     # consume AgentEvent, pinta, responde PermissionRequested
    PrintMode.kt               # sin TTY: imprime texto final, Ask => Deny
  prompt/
    SystemPrompt.kt            # ensambla: base + KOTYCLI.md + lista de skills + entorno
```

Regla de dependencias entre paquetes (se verifica en CI):

```
ui -> core, config
core -> providers (solo la interfaz), tools (solo la interfaz)
tools -> core
providers/<x> -> providers (interfaz), core.Message
nadie -> ui
nadie fuera de providers/anthropic -> com.anthropic.*
```

## Ficheros en disco

```
~/.kotycli/
  config.json
  settings.json          # reglas de permisos persistidas
  KOTYCLI.md
  skills/
  agents/
  logs/
  sessions/<id>.jsonl    # historial serializado, para --resume (fase 2)

<repo>/.kotycli/
  config.json            # override por proyecto
  settings.json
  skills/
  agents/
<repo>/KOTYCLI.md
```

## Roadmap

**Fase 0. Esqueleto.** Gradle + shadow + clikt. `kotycli "hola"` llama al proveedor Anthropic, imprime la respuesta. Modelo neutral de mensajes y `AnthropicProvider` con tests de traducción.

**Fase 1. Loop y tools.** `AgentLoop`, `ToolDispatcher`, `bash`, `read`, `write`, `edit`. Permisos en modo `default` y `yolo` con `Ask` por terminal. Modo `--print`. Aquí ya se puede trabajar con él.

**Fase 2. Contexto y calidad de vida.** `glob`, `grep`, truncado, compactación, `KOTYCLI.md`, streaming en la TUI, allowlist de permisos persistida, `--resume`.

**Fase 3. Skills y subagentes.** `SkillLoader`, tool `skill`, `/skill`, `AgentDefinition`, tool `agent`, `explore` builtin.

**Fase 4. Independencia real.** `OpenAiCompatProvider`, configuración multi-proveedor, test de arquitectura que prohíbe imports de proveedor fuera de su paquete.

**Fase 5. Extensión.** Cliente MCP (stdio primero) para tools externas. Hooks (`pre-tool`, `post-tool`) como scripts. Sesiones en background.

Cada fase termina con algo usable. No se empieza la siguiente con la anterior a medias.
