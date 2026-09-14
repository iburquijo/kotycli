package dev.kotycli.interceptors

import dev.kotycli.core.AgentContext
import dev.kotycli.core.Block
import dev.kotycli.core.ToolInterceptor
import dev.kotycli.tools.Tool

/** Corta resultados largos conservando cabeza y cola, avisando en el propio texto. Las tools no truncan por su cuenta. */
class Truncate(private val maxChars: Int = 40_000) : ToolInterceptor {
    override suspend fun after(ctx: AgentContext, tool: Tool, call: Block.ToolUse, result: Block.ToolResult): Block.ToolResult {
        if (result.content.length <= maxChars) return result
        return result.copy(content = truncate(result.content, maxChars))
    }

    companion object {
        fun truncate(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val head = (maxChars * 2) / 3
            val tail = maxChars - head
            val omitted = text.length - head - tail
            return text.substring(0, head) +
                "\n\n[... $omitted caracteres omitidos de un total de ${text.length}; acota la salida (offset/limit, head, grep) si necesitas esa parte ...]\n\n" +
                text.substring(text.length - tail)
        }
    }
}
