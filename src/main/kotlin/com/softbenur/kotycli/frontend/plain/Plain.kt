package com.softbenur.kotycli.frontend.plain

import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.frontend.Render
import com.softbenur.kotycli.frontend.Session
import com.softbenur.kotycli.frontend.parseReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.PrintStream

/**
 * Modo terminal tonto: sin ANSI, sin raw mode, sin JLine. Lee líneas de stdin, escribe texto a stdout.
 * Funciona en comint, `M-x shell`, pipes y CI.
 */
class Plain(
    private val session: Session,
    private val input: BufferedReader = System.`in`.bufferedReader(),
    private val out: PrintStream = System.out,
    private val interactive: Boolean = System.console() != null,
    private val assumeYes: Boolean = false,
) {
    suspend fun runOnce(prompt: String) = coroutineScope {
        val printer = subscribe(this)
        session.turn(prompt)
        printer.cancelAndJoin()
    }

    suspend fun runInteractive() = coroutineScope {
        val printer = subscribe(this)
        out.println("kotycli (--plain). Escribe un mensaje y Enter; /exit para salir.")
        while (true) {
            out.print("> ")
            out.flush()
            val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text == "/exit" || text == "/quit") break
            session.turn(text)
        }
        printer.cancelAndJoin()
    }

    private fun subscribe(scope: CoroutineScope): Job = scope.launch {
        session.root.events.collect { e -> render(e) }
    }

    private suspend fun render(e: AgentEvent) {
        when (e) {
            is AgentEvent.TextDelta -> { out.print(e.text); out.flush() }
            is AgentEvent.AssistantMessage -> if (e.message.text.isNotEmpty()) out.println()
            is AgentEvent.ToolStart -> out.println(Render.toolLine(e))
            is AgentEvent.ToolEnd -> out.println(Render.resultLine(e))
            is AgentEvent.PermissionAsk -> e.reply.complete(askPermission(e))
            is AgentEvent.SubagentStart -> out.println("● task ${e.agentType} «${e.prompt.take(80)}»")
            is AgentEvent.SubagentEnd -> out.println("  ✓ subagente ${e.agentId}: ${Render.tokens(e.tokensBurned)} tokens quemados, ${e.tokensReturned} caracteres devueltos")
            is AgentEvent.Compacted -> out.println("[contexto compactado: ${Render.tokens(e.before)} -> ${Render.tokens(e.after)} tokens]")
            is AgentEvent.BudgetExceeded -> out.println("[presupuesto agotado: ${e.what}]")
            is AgentEvent.Refusal -> out.println("[el modelo ha rechazado continuar]")
            is AgentEvent.Failed -> out.println("[error del proveedor: ${e.message}]")
            is AgentEvent.TurnEnd -> if (e.agentId == session.root.id) out.println("— ${Render.tokens(e.usage.total)} tokens · ${Render.formatMs(e.durationMs)}")
            is AgentEvent.UsageUpdate -> {}
        }
    }

    private suspend fun askPermission(e: AgentEvent.PermissionAsk): PermissionReply {
        if (assumeYes) return PermissionReply.ALLOW
        out.println(Render.permissionPrompt(e))
        if (!interactive) {
            out.println("  [sin TTY: denegado]")
            return PermissionReply.DENY
        }
        out.print("  ¿Permitir? [s] sí · [S] siempre en esta sesión · [n] no > ")
        out.flush()
        val line = withContext(Dispatchers.IO) { input.readLine() }
        return parseReply(line)
    }
}
