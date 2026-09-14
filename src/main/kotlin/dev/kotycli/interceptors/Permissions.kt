package dev.kotycli.interceptors

import dev.kotycli.core.AgentContext
import dev.kotycli.core.AgentEvent
import dev.kotycli.core.Block
import dev.kotycli.core.PermissionMode
import dev.kotycli.core.PermissionReply
import dev.kotycli.core.ToolInterceptor
import dev.kotycli.tools.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

sealed interface Decision {
    data object Allow : Decision
    data object Ask : Decision
    data class Deny(val reason: String) : Decision
}

/** Regla `tool(patrón)`: `bash(git status*)`, `create(C:\Windows\*)`, `edit` (sin patrón = cualquier input). `*` casa con cualquier cosa, barras incluidas. */
data class Rule(val tool: String, val pattern: String, val decision: Kind) {
    enum class Kind { ALLOW, ASK, DENY }

    private val regex: Regex by lazy { globToRegex(pattern) }

    fun matches(toolName: String, subject: String): Boolean = tool == toolName && regex.matches(subject)

    override fun toString() = if (pattern == "*") tool else "$tool($pattern)"

    companion object {
        fun parse(spec: String, kind: Kind): Rule {
            val s = spec.trim()
            val open = s.indexOf('(')
            if (open < 0) return Rule(s, "*", kind)
            require(s.endsWith(")")) { "Regla mal formada: $spec" }
            return Rule(s.substring(0, open).trim(), s.substring(open + 1, s.length - 1), kind)
        }

        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' -> { sb.append(".*"); while (i + 1 < glob.length && glob[i + 1] == '*') i++ }
                    c == '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.append('$').toString(), RegexOption.IGNORE_CASE)
        }
    }
}

interface PermissionPolicy {
    fun decide(ctx: AgentContext, tool: Tool, input: JsonObject): Decision
}

/**
 * Política por modo + reglas. Deny gana a allow, allow gana a ask. Las reglas de sesión
 * ("permitir siempre en esta sesión") se añaden en caliente.
 */
class RulePolicy(rules: List<Rule> = emptyList()) : PermissionPolicy {
    private val rules = CopyOnWriteArrayList(rules)

    fun addSessionRule(rule: Rule) { rules += rule }
    fun rules(): List<Rule> = rules.toList()

    override fun decide(ctx: AgentContext, tool: Tool, input: JsonObject): Decision {
        val subject = subjectOf(tool, input)
        val matching = rules.filter { it.matches(tool.name, subject) }
        matching.firstOrNull { it.decision == Rule.Kind.DENY }?.let { return Decision.Deny("regla ${it}") }
        if (matching.any { it.decision == Rule.Kind.ALLOW }) return Decision.Allow
        if (matching.any { it.decision == Rule.Kind.ASK }) return Decision.Ask
        return byMode(ctx.config.permissionMode, tool)
    }

    private fun byMode(mode: PermissionMode, tool: Tool): Decision = when (mode) {
        PermissionMode.YOLO -> Decision.Allow
        PermissionMode.ACCEPT_EDITS -> if (tool.name == "bash") Decision.Ask else Decision.Allow
        PermissionMode.DEFAULT -> if (tool.readOnly || tool.name == "task") Decision.Allow else Decision.Ask
    }

    companion object {
        /** Lo que se compara contra el patrón de la regla: el comando en `bash`, la ruta en las tools de fichero. */
        fun subjectOf(tool: Tool, input: JsonObject): String {
            val field = when {
                tool.name == "bash" -> "command"
                tool.pathFields.isNotEmpty() -> tool.pathFields.first()
                else -> null
            }
            return field?.let { input[it]?.jsonPrimitive?.content }.orEmpty()
        }
    }
}

/**
 * El modelo propone, este interceptor decide (ADR 0005). Un `Ask` se materializa como
 * `AgentEvent.PermissionAsk`; sin frontend que responda, se resuelve como `Deny`.
 */
class Permissions(
    val policy: RulePolicy,
    private val askTimeout: Duration = 10.minutes,
) : ToolInterceptor {
    override suspend fun before(ctx: AgentContext, tool: Tool, call: Block.ToolUse): Block.ToolResult? {
        return when (val decision = policy.decide(ctx, tool, call.input)) {
            Decision.Allow -> null
            is Decision.Deny -> denied(call, "denegado por ${decision.reason}")
            Decision.Ask -> ask(ctx, tool, call)
        }
    }

    private suspend fun ask(ctx: AgentContext, tool: Tool, call: Block.ToolUse): Block.ToolResult? {
        if (ctx.events.subscriptionCount.value == 0) return denied(call, "no hay nadie que pueda aprobar la operación")
        val subject = RulePolicy.subjectOf(tool, call.input)
        val reply = CompletableDeferred<PermissionReply>()
        ctx.emit(AgentEvent.PermissionAsk(ctx.id, tool.name, call.input, subject, reply))
        val answer = withTimeoutOrNull(askTimeout) { reply.await() } ?: PermissionReply.DENY
        return when (answer) {
            PermissionReply.ALLOW -> null
            PermissionReply.ALLOW_SESSION -> {
                policy.addSessionRule(sessionRule(tool, subject))
                null
            }
            PermissionReply.DENY -> denied(call, "el usuario lo ha rechazado")
        }
    }

    private fun sessionRule(tool: Tool, subject: String): Rule =
        if (tool.name == "bash") Rule(tool.name, subject.replace("*", "\\*"), Rule.Kind.ALLOW) else Rule(tool.name, "*", Rule.Kind.ALLOW)

    private fun denied(call: Block.ToolUse, why: String) =
        Block.ToolResult(call.id, "Operación no permitida: $why. Propón otra forma de hacerlo o pide al usuario que lo haga.", isError = true)
}
