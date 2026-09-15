package com.softbenur.kotycli.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/** Respuesta del usuario a un `PermissionAsk`. */
enum class PermissionReply { ALLOW, ALLOW_SESSION, DENY }

/**
 * Todo lo que el loop cuenta hacia arriba. El core no imprime nada: emite esto en un
 * `SharedFlow` etiquetado por agente y cada frontend decide cómo pintarlo.
 */
sealed interface AgentEvent {
    val agentId: String

    data class TextDelta(override val agentId: String, val text: String) : AgentEvent
    data class AssistantMessage(override val agentId: String, val message: Message) : AgentEvent
    data class ToolStart(override val agentId: String, val call: Block.ToolUse) : AgentEvent
    data class ToolEnd(override val agentId: String, val call: Block.ToolUse, val result: Block.ToolResult, val durationMs: Long) : AgentEvent
    data class PermissionAsk(
        override val agentId: String,
        /** Id de la tool call que espera permiso: ACP lo necesita para correlacionarla con su `tool_call`. */
        val callId: String,
        val toolName: String,
        val input: JsonObject,
        val subject: String,
        val reply: CompletableDeferred<PermissionReply>,
    ) : AgentEvent
    data class SubagentStart(override val agentId: String, val parentId: String, val agentType: String, val prompt: String) : AgentEvent
    data class SubagentEnd(override val agentId: String, val tokensBurned: Int, val tokensReturned: Int) : AgentEvent
    data class Compacted(override val agentId: String, val before: Int, val after: Int) : AgentEvent
    data class UsageUpdate(override val agentId: String, val usage: Usage, val sessionTotal: Int) : AgentEvent
    data class BudgetExceeded(override val agentId: String, val what: String) : AgentEvent
    data class Refusal(override val agentId: String) : AgentEvent
    /** El proveedor falló (red, 4xx/5xx, wire ilegible). El turn termina; el historial queda como estaba. */
    data class Failed(override val agentId: String, val message: String) : AgentEvent
    data class TurnEnd(override val agentId: String, val usage: Usage, val durationMs: Long) : AgentEvent
}
