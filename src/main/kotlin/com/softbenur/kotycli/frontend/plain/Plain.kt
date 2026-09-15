package com.softbenur.kotycli.frontend.plain

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
        out.println("kotycli (--plain). Escribe un mensaje y Enter; /help lista los comandos.")
        while (true) {
            out.print("> ")
            out.flush()
            val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
            if (line.isBlank()) continue
            if (!handle(Commands.parse(line, session.skills))) break
        }
        printer.cancelAndJoin()
    }

    /** Los mismos comandos que la TUI. `/edit` necesita entregarle el terminal al editor, así que pide TTY. */
    private suspend fun handle(command: Command): Boolean {
        when (command) {
            is Command.Exit -> return false
            is Command.Help -> out.println(Commands.help(session.skills))
            is Command.Config -> out.println(session.describe())
            is Command.Reload -> out.println(session.reload())
            is Command.Copy -> copyLastAnswer()
            is Command.Compact -> when (val result = session.compact(command.instructions)) {
                is CompactResult.NothingToDo -> out.println("[no hay bastante conversación que resumir]")
                is CompactResult.Failed -> out.println("[no se ha podido compactar: ${result.message}]")
                is CompactResult.Done -> {} // el evento Compacted ya lo ha contado el suscriptor
            }
            is Command.Edit -> if (!interactive) out.println("[/edit necesita un terminal]") else {
                when (val result = Editor.open(command.initial)) {
                    is EditResult.Text -> session.turn(result.text)
                    is EditResult.Empty -> out.println("[nada que mandar]")
                    is EditResult.Failed -> out.println("[${result.message}]")
                }
            }
            is Command.Skill -> session.turn(command.expanded)
            is Command.Unknown -> out.println("[comando desconocido: /${command.name}; con /help salen todos]")
            is Command.Prompt -> session.turn(command.text)
        }
        return true
    }

    private fun copyLastAnswer() {
        val text = session.root.finalText().trim()
        if (text.isEmpty()) { out.println("[todavía no hay ninguna respuesta que copiar]"); return }
        val how = Clipboard.copy(text)
        if (how == null) { out.print(Clipboard.osc52(text)); out.flush() }
        out.println("[copiados ${text.length} caracteres (${how ?: "OSC 52"})]")
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
            is AgentEvent.TurnEnd -> if (e.agentId == session.root.id) {
                if (!e.answered) out.println("[el modelo ha terminado sin contestar nada]")
                out.println("— ${Render.tokens(e.usage.total)} tokens · ${Render.formatMs(e.durationMs)}")
            }
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
