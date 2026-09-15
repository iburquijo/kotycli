package com.softbenur.kotycli.frontend.tui

import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.CompactResult
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.frontend.Clipboard
import com.softbenur.kotycli.frontend.Command
import com.softbenur.kotycli.frontend.Commands
import com.softbenur.kotycli.frontend.EditResult
import com.softbenur.kotycli.frontend.Editor
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
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.ParsedLine
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.completer.StringsCompleter
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

/**
 * TUI append-only (ADR 0008): el transcript fluye hacia arriba como un log, solo el prompt está vivo.
 * Nunca pantalla completa, nunca framework de repintado.
 */
class Tui(private val session: Session, private val banner: String) {
    private val terminal: Terminal = buildTerminal()
    private val completer = SessionCompleter { Commands.builtins + session.skills.names().map { "/$it" } }
    private val reader: LineReader = LineReaderBuilder.builder().terminal(terminal).completer(completer).build()
    private val out get() = terminal.writer()

    suspend fun run() = coroutineScope {
        val printer = subscribe(this)
        styled(banner, AttributedStyle.DEFAULT.faint())
        styled("Escribe un mensaje y Enter. Ctrl+C cancela el turn en curso; /help lista los comandos.", AttributedStyle.DEFAULT.faint())
        while (true) {
            val line = try {
                withContext(Dispatchers.IO) { reader.readLine("\n> ") }
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                break
            }
            if (line.isBlank()) continue
            if (!handle(this, Commands.parse(line, session.skills))) break
        }
        printer.cancelAndJoin()
        terminal.close()
    }

    /** Ejecuta un comando ya interpretado. Devuelve false cuando toca cerrar la sesión. */
    private suspend fun handle(scope: CoroutineScope, command: Command): Boolean {
        when (command) {
            is Command.Exit -> return false
            is Command.Help -> styled(Commands.help(session.skills), AttributedStyle.DEFAULT.faint())
            is Command.Config -> styled(session.describe(), AttributedStyle.DEFAULT.faint())
            is Command.Copy -> copyLastAnswer()
            is Command.Reload -> styled(session.reload(), AttributedStyle.DEFAULT.faint())
            is Command.Compact -> compact(scope, command.instructions)
            is Command.Edit -> when (val result = Editor.open(command.initial)) {
                is EditResult.Text -> runTurn(scope, result.text)
                is EditResult.Empty -> styled("[nada que mandar]", AttributedStyle.DEFAULT.faint())
                is EditResult.Failed -> warn(result.message)
            }
            is Command.Skill -> {
                styled("● skill ${command.name}", AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA))
                runTurn(scope, command.expanded)
            }
            is Command.Unknown -> warn("Comando desconocido: /${command.name}. Con /help salen todos.")
            is Command.Prompt -> runTurn(scope, command.text)
        }
        return true
    }

    private suspend fun runTurn(scope: CoroutineScope, text: String) = cancelable(scope) { session.turn(text) }

    /** El evento `Compacted` ya lo pinta el suscriptor: aquí solo se cuenta lo que no llega a compactarse. */
    private suspend fun compact(scope: CoroutineScope, instructions: String?) {
        styled("[compactando…]", AttributedStyle.DEFAULT.faint())
        var result: CompactResult? = null
        cancelable(scope) { result = session.compact(instructions) }
        when (val r = result) {
            is CompactResult.NothingToDo -> styled("[no hay bastante conversación que resumir]", AttributedStyle.DEFAULT.faint())
            is CompactResult.Failed -> warn("No se ha podido compactar: ${r.message}")
            is CompactResult.Done -> {} // el `Compacted` con el antes -> después ya lo ha pintado el suscriptor
            null -> {} // cancelado con Ctrl+C
        }
    }

    /** Un trabajo que Ctrl+C corta: mientras corre, la señal INT cancela el job en vez de matar el proceso. */
    private suspend fun cancelable(scope: CoroutineScope, block: suspend () -> Unit) {
        val job = scope.launch { block() }
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

    private fun copyLastAnswer() {
        val text = session.root.finalText().trim()
        if (text.isEmpty()) { warn("Todavía no hay ninguna respuesta que copiar."); return }
        val how = Clipboard.copy(text)
        if (how == null) { out.print(Clipboard.osc52(text)); out.flush() }
        styled("[copiados ${text.length} caracteres (${how ?: "OSC 52"})]", AttributedStyle.DEFAULT.faint())
    }

    private fun warn(text: String) = styled(text, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))

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

/**
 * JLine 3.25 separó el charset interno del terminal (`encoding`) del que usa el writer
 * (`stdoutEncoding`), y este último lo deduce de `stdout.encoding`/`native.encoding`. Sin `LANG` UTF-8
 * —Windows, `docker run` pelado, `systemd`— salen `ANSI_X3.4-1968` y los acentos y los `·` se van a `?`.
 * `Main.ensureUtf8Stdout()` no llega aquí: la TUI no escribe por `System.out` sino por `terminal.writer()`.
 */
fun terminalBuilder(): TerminalBuilder = TerminalBuilder.builder()
    .system(true)
    .encoding(Charsets.UTF_8)
    .stdinEncoding(Charsets.UTF_8)
    .stdoutEncoding(Charsets.UTF_8)
    .stderrEncoding(Charsets.UTF_8)

fun buildTerminal(): Terminal = terminalBuilder().build()

/** Los candidatos se recalculan en cada TAB porque `/reload` puede haber cambiado la lista de skills. */
private class SessionCompleter(private val candidates: () -> List<String>) : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) =
        StringsCompleter(this.candidates()).complete(reader, line, candidates)
}
