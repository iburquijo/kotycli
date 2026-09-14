package com.softbenur.kotycli.frontend.tui

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
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

/**
 * TUI append-only (ADR 0008): el transcript fluye hacia arriba como un log, solo el prompt está vivo.
 * Nunca pantalla completa, nunca framework de repintado.
 */
class Tui(private val session: Session, private val banner: String) {
    private val terminal: Terminal = TerminalBuilder.builder().system(true).encoding(Charsets.UTF_8).build()
    private val reader: LineReader = LineReaderBuilder.builder().terminal(terminal).build()
    private val out get() = terminal.writer()

    suspend fun run() = coroutineScope {
        val printer = subscribe(this)
        styled(banner, AttributedStyle.DEFAULT.faint())
        styled("Escribe un mensaje y Enter. Ctrl+C cancela el turn en curso; /exit sale.", AttributedStyle.DEFAULT.faint())
        while (true) {
            val line = try {
                withContext(Dispatchers.IO) { reader.readLine("\n> ") }
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                break
            }
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text == "/exit" || text == "/quit") break
            if (text == "/help") { help(); continue }
            runTurn(this, text)
        }
        printer.cancelAndJoin()
        terminal.close()
    }

    private suspend fun runTurn(scope: CoroutineScope, text: String) {
        val job = scope.launch { session.turn(text) }
        val previous = terminal.handle(Terminal.Signal.INT) {
            styled("\n[cancelando…]", AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
            job.cancel()
        }
        try {
            job.join()
        } finally {
            terminal.handle(Terminal.Signal.INT, previous)
        }
    }

    private fun help() {
        styled("/exit   salir\n/help   esta ayuda\n(/compact, /config, /copy, /edit y /<skill> llegan en v1.1 y v2)", AttributedStyle.DEFAULT.faint())
    }

    private fun subscribe(scope: CoroutineScope): Job = scope.launch {
        session.root.events.collect { e -> render(e) }
    }

    private suspend fun render(e: AgentEvent) {
        val child = e.agentId != session.root.id
        val indent = if (child) "│ " else ""
        when (e) {
            is AgentEvent.TextDelta -> if (!child) { out.print(e.text); out.flush() }
            is AgentEvent.AssistantMessage -> if (!child && e.message.text.isNotEmpty()) out.println()
            is AgentEvent.ToolStart -> styled(indent + Render.toolLine(e), if (child) AttributedStyle.DEFAULT.faint() else AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
            is AgentEvent.ToolEnd -> styled(indent + Render.resultLine(e), if (e.result.isError) AttributedStyle.DEFAULT.foreground(AttributedStyle.RED) else AttributedStyle.DEFAULT.faint())
            is AgentEvent.PermissionAsk -> e.reply.complete(askPermission(e))
            is AgentEvent.SubagentStart -> styled("● task ${e.agentType} «${e.prompt.lineSequence().first().take(80)}»", AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA))
            is AgentEvent.SubagentEnd -> styled("  ✓ ${Render.tokens(e.tokensBurned)} tokens quemados · ${e.tokensReturned} caracteres devueltos", AttributedStyle.DEFAULT.faint())
            is AgentEvent.Compacted -> styled("── contexto compactado: ${Render.tokens(e.before)} -> ${Render.tokens(e.after)} tokens ──", AttributedStyle.DEFAULT.faint())
            is AgentEvent.BudgetExceeded -> styled("[presupuesto agotado: ${e.what}]", AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
            is AgentEvent.Refusal -> styled("[el modelo ha rechazado continuar]", AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
            is AgentEvent.Failed -> styled("[error del proveedor: ${e.message}]", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
            is AgentEvent.TurnEnd -> if (!child) styled("── ${Render.tokens(e.usage.total)} tokens · ${Render.formatMs(e.durationMs)} · sesión ${Render.tokens(session.root.budget.tokensConsumed)} ──", AttributedStyle.DEFAULT.faint())
            is AgentEvent.UsageUpdate -> {}
        }
    }

    private suspend fun askPermission(e: AgentEvent.PermissionAsk): PermissionReply {
        styled("\n" + Render.permissionPrompt(e), AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
        if (Render.isDestructive(e)) styled("  ⚠ parece destructivo", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold())
        val line = try {
            withContext(Dispatchers.IO) { reader.readLine("  [s] permitir · [S] siempre en esta sesión · [n] denegar > ") }
        } catch (e: UserInterruptException) {
            null
        } catch (e: EndOfFileException) {
            null
        }
        return parseReply(line)
    }

    private fun styled(text: String, style: AttributedStyle) {
        out.println(AttributedStringBuilder().style(style).append(text).toAnsi(terminal))
        out.flush()
    }
}
