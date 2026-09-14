package dev.kotycli.core

import dev.kotycli.tools.Tool

/**
 * Punto de corte before/after alrededor de cada ejecución de tool. Esto es todo el "sistema de hooks"
 * (ADR 0009): permisos, logging, guardas de path y truncado son interceptores.
 */
interface ToolInterceptor {
    /** Antes de ejecutar. Devuelve null para continuar o un ToolResult para cortocircuitar (p.ej. denegado). */
    suspend fun before(ctx: AgentContext, tool: Tool, call: Block.ToolUse): Block.ToolResult? = null

    /** Después de ejecutar. Puede transformar el resultado (truncar, redactar, anotar). */
    suspend fun after(ctx: AgentContext, tool: Tool, call: Block.ToolUse, result: Block.ToolResult): Block.ToolResult = result
}
