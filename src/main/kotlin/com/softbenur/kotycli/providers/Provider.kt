package com.softbenur.kotycli.providers

import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.Completion
import com.softbenur.kotycli.core.Message
import com.softbenur.kotycli.core.Role
import com.softbenur.kotycli.core.StopReason
import com.softbenur.kotycli.core.ToolDefinition
import com.softbenur.kotycli.core.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Adaptador de proveedor de modelo. El loop solo usa `stream` y se queda con el `Completion` final. */
interface Provider {
    val id: String
    val capabilities: Capabilities
    fun stream(request: Request): Flow<StreamEvent>
}

data class Request(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
    val maxTokens: Int,
)

data class Capabilities(
    val parallelToolCalls: Boolean = true,
    val promptCaching: Boolean = false,
    /** false => ContextManager no poda, solo compacta. */
    val allowsHistoryEdits: Boolean = true,
    val contextWindow: Int = 0,
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ToolUseStart(val id: String, val name: String) : StreamEvent
    data class ToolInputDelta(val id: String, val json: String) : StreamEvent
    data class Done(val completion: Completion) : StreamEvent
}

class ProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Consume el stream, reenvía cada evento a `onEvent` y devuelve el `Completion` del `Done` final. */
suspend fun Flow<StreamEvent>.collectToCompletion(onEvent: suspend (StreamEvent) -> Unit = {}): Completion {
    var done: Completion? = null
    collect { event ->
        onEvent(event)
        if (event is StreamEvent.Done) done = event.completion
    }
    return done ?: throw ProviderException("El proveedor cerró el stream sin terminar la respuesta")
}

/** Acumula los deltas de un stream en un `Completion`. Útil para adaptadores y para tests. */
class CompletionAccumulator(private val lenientJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {
    private val text = StringBuilder()
    private val toolOrder = mutableListOf<String>()
    private val toolNames = mutableMapOf<String, String>()
    private val toolArgs = mutableMapOf<String, StringBuilder>()
    private val opaque = mutableListOf<Block.Opaque>()

    fun text(delta: String) { text.append(delta) }

    fun toolStart(id: String, name: String) {
        if (id !in toolNames) toolOrder += id
        toolNames[id] = name
        toolArgs.getOrPut(id) { StringBuilder() }
    }

    fun toolArgs(id: String, fragment: String) { toolArgs.getOrPut(id) { StringBuilder() }.append(fragment) }

    fun opaque(block: Block.Opaque) { opaque += block }

    fun hasToolCalls(): Boolean = toolOrder.isNotEmpty()

    fun build(stopReason: StopReason, usage: Usage): Completion {
        val blocks = mutableListOf<Block>()
        if (text.isNotEmpty()) blocks += Block.Text(text.toString())
        blocks += opaque
        for (id in toolOrder) {
            val raw = toolArgs[id]?.toString().orEmpty().ifBlank { "{}" }
            val input = runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: buildJsonObject { } // el argumento roto se verá como input vacío y la tool devolverá un error legible
            blocks += Block.ToolUse(id, toolNames[id] ?: "", input)
        }
        val effectiveStop = if (stopReason == StopReason.END_TURN && toolOrder.isNotEmpty()) StopReason.TOOL_USE else stopReason
        return Completion(Message(Role.ASSISTANT, blocks), effectiveStop, usage)
    }
}
