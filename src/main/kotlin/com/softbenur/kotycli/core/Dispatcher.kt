package com.softbenur.kotycli.core

import com.softbenur.kotycli.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Ejecuta una ronda de tool calls. Las `readOnly` en paralelo, las demás en serie y en el orden
 * que las pidió el modelo. El orden de los resultados sigue siempre el orden de las llamadas.
 */
suspend fun dispatch(ctx: AgentContext, calls: List<Block.ToolUse>): List<Block.ToolResult> = coroutineScope {
    val (readOnly, mutating) = calls.partition { ctx.tools[it.name]?.readOnly == true }
    val parallel = readOnly.map { async { runOne(ctx, it) } }
    val serial = mutating.map { runOne(ctx, it) }
    (parallel.awaitAll() + serial).sortedBy { r -> calls.indexOfFirst { it.id == r.toolUseId } }
}

private suspend fun runOne(ctx: AgentContext, call: Block.ToolUse): Block.ToolResult {
    val tool = ctx.tools[call.name]
        ?: return Block.ToolResult(call.id, "Tool desconocida: ${call.name}. Disponibles: ${ctx.tools.names().joinToString()}", isError = true)

    for (interceptor in ctx.interceptors) {
        interceptor.before(ctx, tool, call)?.let { return it }
    }

    ctx.emit(AgentEvent.ToolStart(ctx.id, call))
    val started = System.nanoTime()
    var result = try {
        withTimeout(tool.timeout) { tool.execute(call.input, ToolContext(ctx, call.id)) }
    } catch (e: TimeoutCancellationException) {
        Block.ToolResult(call.id, "Error: la tool ${tool.name} superó su timeout de ${tool.timeout}", isError = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Block.ToolResult(call.id, "Error: ${e.message ?: e::class.simpleName}", isError = true)
    }
    if (result.toolUseId != call.id) result = result.copy(toolUseId = call.id)

    for (interceptor in ctx.interceptors.asReversed()) {
        result = interceptor.after(ctx, tool, call, result)
    }
    val durationMs = (System.nanoTime() - started) / 1_000_000
    ctx.emit(AgentEvent.ToolEnd(ctx.id, call, result, durationMs))
    return result
}
