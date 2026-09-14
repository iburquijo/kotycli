package com.softbenur.kotycli.tools

import com.softbenur.kotycli.agents.AgentType
import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.ContextManager
import com.softbenur.kotycli.core.runLoop
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Serializable
data class TaskInput(
    @SerialName("agent_type")
    @Description("Rol del subagente")
    val agentType: String,
    @Description("El encargo, completo y autocontenido: el subagente no ve esta conversación ni tu historial")
    val prompt: String,
    @Description("Descripción de 3-5 palabras de la tarea, para mostrar al usuario")
    val description: String? = null,
)

/**
 * Lanza otro `runLoop` con un contexto virgen y devuelve solo su último mensaje (doc 04).
 * Comparte presupuesto, eventos, interceptores y `ToolEnv` con el padre; el historial no.
 */
class TaskTool(
    private val types: Map<String, AgentType>,
    /** Entorno y `AGENTS.md`: lo que el hijo necesita saber aunque no herede el prompt del padre. */
    private val contextPrompt: String = "",
    /** Modelo por defecto de los subagentes (`subagentModel` en la config). */
    private val defaultModel: String? = null,
) : TypedTool<TaskInput>(TaskInput.serializer()) {
    override val name = "task"
    override val readOnly = false
    /** No es readOnly (el hijo puede escribir) pero sí concurrente: varios subagentes corren a la vez. */
    override val parallel = true
    override val timeout: Duration = 30.minutes

    override val description: String = """
        Lanza un subagente con contexto propio y te devuelve solo su respuesta final.
        Úsalo cuando la tarea vaya a generar mucho ruido que no necesitas conservar (búsquedas amplias,
        exploración de un repo grande) o cuando puedas lanzar varias tareas independientes a la vez.
        El subagente no ve tu conversación: el `prompt` tiene que bastarse solo, decir qué buscas y qué
        quieres de vuelta. No te contesta a mitad ni le puedes dar instrucciones después.
        Para leer un fichero concreto o correr un comando, hazlo tú: sale más barato.

        Roles disponibles:
        ${types.values.joinToString("\n") { "- ${it.name}: ${it.description}" }.prependIndent("        ").trim()}
    """.trimIndent()

    override suspend fun run(input: TaskInput, ctx: ToolContext): Block.ToolResult {
        val parent = ctx.agent
        if (parent.depth >= parent.config.maxDepth) {
            return ctx.error("Profundidad máxima de subagentes alcanzada (${parent.config.maxDepth}). Haz la tarea tú mismo.")
        }
        val type = types[input.agentType]
            ?: return ctx.error("agent_type desconocido: '${input.agentType}'. Disponibles: ${types.keys.joinToString()}")

        val child = AgentContext(
            config = parent.config.copy(
                systemPrompt = listOf(type.systemPrompt, contextPrompt).filter { it.isNotBlank() }.joinToString("\n\n"),
                model = type.model ?: defaultModel ?: parent.config.model,
                maxIterationsPerTurn = type.maxIterations,
                permissionRules = parent.config.permissionRules + type.permissionRules,
            ),
            provider = parent.provider,
            tools = parent.tools.restrictedTo(type.tools),
            interceptors = parent.interceptors,
            budget = parent.budget,
            env = parent.env,
            contextManager = ContextManager(),
            events = parent.events,
            depth = parent.depth + 1,
        )

        parent.emit(AgentEvent.SubagentStart(child.id, parent.id, type.name, input.description ?: input.prompt))
        parent.subagentSemaphore.withPermit { runLoop(child, input.prompt) }
        val answer = child.finalText().trim()
        parent.emit(AgentEvent.SubagentEnd(child.id, child.tokensBurned, answer.length))

        return if (answer.isEmpty()) ctx.error("El subagente ${type.name} terminó sin respuesta (presupuesto agotado o error del proveedor).")
        else ctx.ok(answer)
    }
}
