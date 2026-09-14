# 04. Subagentes

## Para qué

Dos motivos, y solo dos:

1. **Aislar contexto.** Explorar un repo grande llena el historial de resultados de `grep` y `read` que luego no sirven. Un subagente hace la exploración en su propio historial y devuelve al padre solo la conclusión.
2. **Paralelizar.** Tres búsquedas independientes pueden correr a la vez en tres subagentes.

No usamos subagentes para "roles" (el planificador, el revisor, el tester) hasta que haya evidencia de que aporta algo. Un subagente es un mecanismo de contexto, no una organización.

## Diseño

Un subagente **es una `Session`** normal que corre el mismo `AgentLoop`. Lo único que cambia es la configuración:

```kotlin
data class AgentDefinition(
    val name: String,                  // "explore", "reviewer", ...
    val description: String,           // cuándo usarlo; esto lo ve el modelo padre
    val systemPrompt: String,
    val tools: Set<String>,            // subconjunto del registry del padre
    val model: String? = null,         // null => el del padre
    val maxIterations: Int = 30,
)
```

Las definiciones se cargan de:

- Builtin: `explore` (solo `read`, `glob`, `grep`, `bash` en modo solo lectura) y `general` (todas las tools menos `agent`).
- `~/.kotycli/agents/*.md` y `.kotycli/agents/*.md`: markdown con frontmatter YAML (`name`, `description`, `tools`, `model`) y el system prompt como cuerpo. Mismo formato que los skills para no inventar dos parsers.

## La tool `agent`

```kotlin
class AgentTool(private val definitions: Map<String, AgentDefinition>) : Tool {
    override val name = "agent"
    override val readOnly = false     // conservador; la sesión hija puede mutar
    // input: { "agent": "explore", "prompt": "...", "description": "..." }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val parent = ctx.session
        if (parent.depth >= parent.config.maxSubagentDepth) return error("Profundidad máxima de subagentes alcanzada")
        val def = definitions[input.agent] ?: return error("Agente desconocido")

        val child = Session(
            id = newId(), depth = parent.depth + 1,
            config = parent.config.copy(model = def.model ?: parent.config.model, maxIterationsPerTurn = def.maxIterations),
            provider = parent.providerFor(def.model),
            tools = parent.tools.restrictedTo(def.tools - "agent"),   // nunca anida por defecto
            permissions = parent.permissions,                         // hereda, no relaja
            context = ContextManager(),
            events = parent.events,                                   // la UI ve el progreso etiquetado con el sessionId hijo
        )
        parent.semaphore.withPermit {                                 // maxConcurrentSubagents
            AgentLoop.runTurn(child, input.prompt)
        }
        return ToolResult(callId, child.finalText(), isError = false)
    }
}
```

Puntos clave:

- **Mismo proceso, misma coroutine tree.** Un subagente es un `async` dentro del `Job` del padre. Cancelar al padre cancela a los hijos. No hay procesos ni hilos dedicados.
- **Devuelve solo el último texto del asistente.** Nada del historial hijo entra en el del padre. Si el padre necesita detalle, se lo pide en el prompt del subagente.
- **Profundidad 1 por defecto.** Un subagente no puede lanzar subagentes. Configurable, pero que alguien lo pida antes de subirlo.
- **Concurrencia acotada** con un `Semaphore` de sesión. El modelo padre puede pedir 10 `agent` en una ronda; se ejecutan de 4 en 4.
- **Permisos heredados.** Si el padre está en `default`, el hijo también. Un `Ask` en el hijo llega a la UI igual que uno del padre, etiquetado.
- **Presupuesto propio de iteraciones** más bajo que el del padre. Un subagente que necesita 50 rondas es una señal de que la tarea estaba mal partida.

## Qué ve la UI

Los `AgentEvent` del hijo van al mismo `SharedFlow` que los del padre, con `sessionId` distinto. La TUI los muestra colapsados bajo una línea "agent explore: buscando usos de X..." y permite expandir. En modo `--print` se ignoran salvo errores.

## Fuera del MVP

- Subagentes en segundo plano que sobreviven al turn del padre.
- Comunicación padre-hijo a mitad de ejecución.
- Subagentes en otro proceso o máquina.
