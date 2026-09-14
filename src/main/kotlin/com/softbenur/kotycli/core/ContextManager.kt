package com.softbenur.kotycli.core

/**
 * Corre antes de cada llamada al modelo. En v1 solo hace el nivel 2 del diseño: sustituir los
 * `ToolResult` más antiguos que `keepRounds` rondas por un marcador cuando el historial se acerca
 * al límite y el proveedor permite editar historial. La compactación (nivel 3) llega con `/compact`.
 */
class ContextManager(
    private val keepRounds: Int = 6,
    private val pruneThreshold: Double = 0.75,
) {
    suspend fun prepare(ctx: AgentContext) {
        val window = ctx.provider.capabilities.contextWindow
        if (window <= 0) return
        if (!ctx.provider.capabilities.allowsHistoryEdits) return
        if (estimateTokens(ctx) < window * pruneThreshold) return
        prune(ctx)
    }

    /** El `usage` de la última respuesta como medida real; antes de la primera respuesta, caracteres/4. */
    fun estimateTokens(ctx: AgentContext): Int {
        val last = ctx.lastUsage
        val sinceLast = ctx.messages.takeLastWhile { it.role == Role.USER }
        val base = last?.let { it.inputTokens + it.outputTokens } ?: (ctx.config.systemPrompt.length / 4)
        return base + sinceLast.sumOf { charCount(it) / 4 }
    }

    private fun charCount(m: Message): Int = m.content.sumOf {
        when (it) {
            is Block.Text -> it.text.length
            is Block.ToolResult -> it.content.length
            is Block.ToolUse -> it.input.toString().length
            is Block.Opaque -> it.payload.toString().length
        }
    }

    private fun prune(ctx: AgentContext) {
        val resultIdx = ctx.messages.withIndex().filter { (_, m) -> m.content.any { it is Block.ToolResult } }.map { it.index }
        val toPrune = resultIdx.dropLast(keepRounds)
        for (i in toPrune) {
            val m = ctx.messages[i]
            if (m.content.all { it is Block.ToolResult && it.content == PRUNED }) continue
            ctx.messages[i] = Message(m.role, m.content.map {
                if (it is Block.ToolResult) it.copy(content = PRUNED) else it
            })
        }
    }

    companion object {
        const val PRUNED = "[resultado descartado para liberar contexto; vuelve a ejecutar la tool si lo necesitas]"
    }
}
