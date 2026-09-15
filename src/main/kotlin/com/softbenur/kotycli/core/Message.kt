package com.softbenur.kotycli.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Modelo neutral de mensajes. Ningún tipo de proveedor entra aquí (ADR 0002). */
enum class Role { USER, ASSISTANT }

data class Message(val role: Role, val content: List<Block>) {
    val text: String get() = content.filterIsInstance<Block.Text>().joinToString("") { it.text }
    val toolUses: List<Block.ToolUse> get() = content.filterIsInstance<Block.ToolUse>()

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(Block.Text(text)))
        fun toolResults(results: List<Block.ToolResult>) = Message(Role.USER, results)
    }
}

sealed interface Block {
    data class Text(val text: String) : Block

    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block

    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : Block

    /**
     * Bloque que solo entiende el proveedor que lo generó (reasoning, compaction, etc.).
     * Se reenvía intacto al mismo proveedor y se descarta si cambia el proveedor.
     */
    data class Opaque(val providerId: String, val payload: JsonElement) : Block
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

/**
 * Tokens de una respuesta. `inputTokens` es **solo la parte no cacheada**: el tamaño real del prompt es
 * `promptTokens`. Los dos wires cuentan distinto y cada adaptador normaliza a esto: en el de OpenAI
 * `prompt_tokens` incluye los cacheados, y en el de Anthropic `input_tokens` los excluye.
 */
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Servidos desde la caché de prefijo; se pagan a una fracción del precio de entrada. */
    val cacheReadTokens: Int = 0,
    /** Escritos a la caché en esta petición; se pagan con recargo. */
    val cacheWriteTokens: Int = 0,
) {
    /** Todo lo que el modelo ha leído como prompt, cacheado o no. Esta es la medida del contexto. */
    val promptTokens: Int get() = inputTokens + cacheReadTokens + cacheWriteTokens

    val total: Int get() = promptTokens + outputTokens

    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
    )
}

data class Completion(val message: Message, val stopReason: StopReason, val usage: Usage)

/** Lo que se manda al proveedor por cada tool: nombre, descripción y JSON Schema del input. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)
