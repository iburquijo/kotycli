# 04. Subagentes

## Para qué

Dos motivos, y solo dos:

1. **Aislar contexto.** Explorar un repo grande llena el historial de resultados de `rg` y `read` que luego no sirven. Un subagente hace la exploración en su propio historial y devuelve al padre solo la conclusión. En el pantallazo de referencia: 41k tokens quemados explorando, 380 devueltos al padre.
2. **Paralelizar.** Tres búsquedas independientes pueden correr a la vez en tres subagentes.

Un subagente es un mecanismo de contexto, no una organización. No hay "planner", "reviewer" ni "tester" hasta que haya evidencia de que aporta algo.

## Diseño: un subagente es una tool más

La tool `task` lanza **otro `runLoop`** con un `AgentContext` virgen. Mismo loop, misma cadena de interceptores, mismo proveedor (o uno más barato por config).

```kotlin
data class AgentType(
    val name: String,                  // "explorer", "implementor", ...
    val description: String,           // cuándo usarlo; esto lo ve el modelo padre
    val systemPrompt: String,
    val tools: Set<String>,            // toolset del rol
    val model: String? = null,         // null => `subagentModel` de la config, o el del padre
    val maxIterations: Int = 30,
    val permissionRules: List<String> = emptyList(),   // "allow:bash(rg *)"; se suman a las del padre, nunca las relajan
)
```

Roles builtin:

| Rol | Toolset | Para qué |
|-----|---------|----------|
| `explorer` | `read`, `fetch`, `bash` con una allowlist de comandos de solo lectura (`rg`, `fd`, `ls`, `cat`, `git log`…) en `permissionRules`; lo que no esté en ella sigue pidiendo permiso | Localizar código, responder "dónde está X" |
| `implementor` | Todo menos `task` | Cambios acotados y bien especificados |

Roles propios en `.agents/agents/*.md` y `~/.agents/agents/*.md`: markdown con frontmatter (`name`, `description`, `tools`, `model`, `maxIterations`, `permissions`) y el system prompt como cuerpo. Mismo formato que los skills para tener un solo parser (`skills/Frontmatter.kt`). Gana el más específico: proyecto > usuario > builtin; un rol sin `description` o sin cuerpo se ignora.

## La tool `task`

```kotlin
class TaskTool(private val types: Map<String, AgentType>) : Tool {
    override val name = "task"
    override val readOnly = false     // conservador; la sesión hija puede mutar
    override val parallel = true      // pero varios `task` de la misma ronda sí corren a la vez
    // input: { "agent_type": "explorer", "prompt": "...", "description": "..." }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val parent = ctx.agent
        if (parent.depth >= parent.config.maxDepth) return error("Profundidad máxima de subagentes alcanzada")
        val type = types[input.agent_type] ?: return error("agent_type desconocido. Disponibles: ${types.keys}")

        val child = AgentContext(
            id = newId(), depth = parent.depth + 1,
            // El prompt del rol más el bloque de entorno y `AGENTS.md`: el hijo no hereda el prompt del padre pero sí dónde está.
            config = parent.config.copy(model = type.model ?: subagentModel ?: parent.config.model, maxIterationsPerTurn = type.maxIterations, systemPrompt = type.systemPrompt + contextPrompt),
            provider = parent.provider,                  // el modelo se cambia por config; el endpoint es el mismo
            tools = parent.tools.restrictedTo(type.tools),
            interceptors = parent.interceptors,          // misma política, mismo log
            budget = parent.budget,                      // los tokens del hijo se descuentan del padre
            contextManager = ContextManager(),
            events = parent.events,                      // el frontend ve el progreso etiquetado con el id hijo
        )
        parent.events.emit(SubagentStart(child.id, parent.id, type.name, input.prompt))
        parent.semaphore.withPermit { runLoop(child, input.prompt) }
        parent.events.emit(SubagentEnd(child.id, child.tokensBurned, child.finalText().length))
        return ok(child.finalText())
    }
}
```

Contrato:

- **Contexto virgen.** El hijo no ve el historial del padre. Todo lo que necesita va en el `prompt`.
- **Solo el mensaje final vuelve.** Nada del transcript hijo entra en el del padre.
- **Presupuesto descontado del padre.** Mismo objeto `Budget`. Un subagente no es tokens gratis.
- **Profundidad máxima 2.** Raíz lanza hijos; los hijos pueden lanzar nietos si su toolset incluye `task` (`implementor` no lo incluye por defecto). Más allá, error.
- **Concurrencia acotada** con un `Semaphore` (4 por defecto). El padre puede pedir diez `task` en una ronda; corren de cuatro en cuatro.
- **Cancelación en cascada.** El hijo es un `async` dentro del `Job` del padre. Ctrl+C mata a todos.
- **Permisos heredados.** Un `Ask` en el hijo llega al frontend igual que uno del padre, etiquetado con el id hijo.

## Qué ve el frontend

Los eventos del hijo van al mismo `SharedFlow` con `agentId` distinto y `SubagentStart` enlaza al padre. La TUI los muestra colapsados e indentados bajo la línea `● task explorer «...»`, con las tools del hijo en tenue, y al terminar el resumen `✓ 4 tools · 41.2k tokens quemados · 380 devueltos`. En ACP se mapean a `session/update` con el mismo etiquetado. En `--plain` se imprime solo la línea de inicio y la de fin.

## Fuera del alcance

- Subagentes en segundo plano que sobreviven al turn del padre.
- Comunicación padre-hijo a mitad de ejecución.
- Subagentes en otro proceso o máquina.
