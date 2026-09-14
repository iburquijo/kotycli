package com.softbenur.kotycli.core

import com.softbenur.kotycli.providers.Provider
import com.softbenur.kotycli.providers.Request
import com.softbenur.kotycli.tools.ToolEnv
import com.softbenur.kotycli.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Semaphore
import java.util.UUID

enum class PermissionMode(val cli: String) {
    DEFAULT("default"),
    ACCEPT_EDITS("accept-edits"),
    YOLO("yolo");

    companion object {
        fun parse(value: String): PermissionMode =
            entries.firstOrNull { it.cli == value || it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Modo de permisos desconocido: $value (válidos: ${entries.joinToString { it.cli }})")
    }
}

data class AgentConfig(
    val systemPrompt: String,
    val model: String,
    val maxOutputTokens: Int = 8192,
    val maxIterationsPerTurn: Int = 50,
    val maxDepth: Int = 2,
    val maxConcurrentSubagents: Int = 4,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
)

/**
 * Historial + configuración de una conversación. Uno por conversación y uno nuevo por cada subagente,
 * que comparte `budget`, `events`, `interceptors` y `env` con el padre.
 */
class AgentContext(
    val config: AgentConfig,
    val provider: Provider,
    val tools: ToolRegistry,
    val interceptors: List<ToolInterceptor>,
    val budget: Budget,
    val env: ToolEnv,
    val contextManager: ContextManager = ContextManager(),
    val events: MutableSharedFlow<AgentEvent> = MutableSharedFlow(extraBufferCapacity = 4096),
    val depth: Int = 0,
    val id: String = newId(),
) {
    val messages: MutableList<Message> = mutableListOf()

    /** Acota los subagentes concurrentes que lanza este contexto. */
    val subagentSemaphore = Semaphore(config.maxConcurrentSubagents)

    /** Tokens quemados por este contexto (no por la sesión entera; para eso está `budget`). */
    var tokensBurned: Int = 0
        private set

    /** Último `usage` reportado por el proveedor: la medida real del tamaño del contexto. */
    var lastUsage: Usage? = null
        private set

    fun request(): Request = Request(
        model = config.model,
        system = config.systemPrompt,
        messages = messages.toList(),
        tools = tools.definitions(),
        maxTokens = config.maxOutputTokens,
    )

    fun recordUsage(usage: Usage) {
        tokensBurned += usage.total
        lastUsage = usage
        budget.consume(usage)
    }

    /** Texto del último mensaje del asistente, lo único que un subagente devuelve al padre. */
    fun finalText(): String = messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()

    suspend fun emit(event: AgentEvent) = events.emit(event)

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
