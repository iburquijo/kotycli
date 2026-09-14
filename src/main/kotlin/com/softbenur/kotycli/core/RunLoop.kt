package com.softbenur.kotycli.core

import com.softbenur.kotycli.providers.StreamEvent
import com.softbenur.kotycli.providers.collectToCompletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * El loop. Un turn: desde que entra un mensaje de usuario hasta que el modelo termina sin pedir tools.
 * No sabe de proveedores ni de frontends; se reutiliza tal cual para los subagentes.
 */
suspend fun runLoop(ctx: AgentContext, userInput: String) {
    ctx.messages += Message.user(userInput)
    var iterations = 0
    var turnUsage = Usage()
    val started = System.nanoTime()

    try {
        while (true) {
            if (++iterations > ctx.config.maxIterationsPerTurn) {
                ctx.emit(AgentEvent.BudgetExceeded(ctx.id, "iteraciones (${ctx.config.maxIterationsPerTurn} por turn)"))
                return
            }
            if (ctx.budget.exceeded()) {
                ctx.emit(AgentEvent.BudgetExceeded(ctx.id, "tokens (${ctx.budget.maxTokens} por sesión)"))
                return
            }
            ctx.contextManager.prepare(ctx)

            val completion = try {
                ctx.provider.stream(ctx.request()).collectToCompletion { event ->
                    if (event is StreamEvent.TextDelta) ctx.emit(AgentEvent.TextDelta(ctx.id, event.text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.emit(AgentEvent.Failed(ctx.id, e.message ?: e::class.simpleName ?: "error"))
                return
            }

            ctx.messages += completion.message
            ctx.recordUsage(completion.usage)
            turnUsage += completion.usage
            ctx.emit(AgentEvent.AssistantMessage(ctx.id, completion.message))
            ctx.emit(AgentEvent.UsageUpdate(ctx.id, completion.usage, ctx.budget.tokensConsumed))

            when (completion.stopReason) {
                StopReason.END_TURN, StopReason.OTHER -> return
                StopReason.REFUSAL -> {
                    ctx.emit(AgentEvent.Refusal(ctx.id))
                    return
                }
                StopReason.MAX_TOKENS -> {
                    if (completion.message.toolUses.isNotEmpty()) {
                        // Se cortó a mitad de tool call: cerramos la ronda con errores para no dejar ToolUse huérfanos.
                        ctx.messages += Message.toolResults(completion.message.toolUses.map {
                            Block.ToolResult(it.id, "Respuesta cortada por max_tokens antes de completar la llamada.", isError = true)
                        })
                    }
                    ctx.messages += Message.user("Continúa donde lo dejaste.")
                }
                StopReason.TOOL_USE -> {
                    val calls = completion.message.toolUses
                    if (calls.isEmpty()) return
                    val results = try {
                        dispatch(ctx, calls)
                    } catch (e: CancellationException) {
                        // Historial append-only: cerramos la ronda con un error por llamada, nunca borramos el ToolUse.
                        withContext(NonCancellable) {
                            ctx.messages += Message.toolResults(calls.map { Block.ToolResult(it.id, "cancelado", isError = true) })
                        }
                        throw e
                    }
                    ctx.messages += Message.toolResults(results)
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            ctx.emit(AgentEvent.TurnEnd(ctx.id, turnUsage, (System.nanoTime() - started) / 1_000_000))
        }
    }
}
