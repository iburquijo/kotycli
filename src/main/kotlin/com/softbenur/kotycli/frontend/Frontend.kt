package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.skills.SkillCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Trozos que comparten la TUI y `--plain`: cómo resumir en una línea una tool call y su resultado. */
object Render {
    fun toolLine(event: AgentEvent.ToolStart): String {
        val input = event.call.input
        val subject = when (event.call.name) {
            "bash" -> input.str("description")?.let { "$it  ·  ${input.str("command").orEmpty()}" } ?: input.str("command").orEmpty()
            "read", "edit", "create" -> input.str("path").orEmpty()
            else -> input.toString()
        }
        return "● ${event.call.name}  ${subject.lineSequence().first().take(160)}"
    }

    fun resultLine(event: AgentEvent.ToolEnd): String {
        val content = event.result.content.trim()
        val summary = when {
            content.isEmpty() -> "(sin salida)"
            event.result.isError -> content.lineSequence().first()
            else -> {
                val lines = content.lines()
                if (lines.size <= 1) content.take(120) else "${lines.first().take(100)} … (${lines.size} líneas)"
            }
        }
        return "  └ ${if (event.result.isError) "✗ " else ""}$summary (${formatMs(event.durationMs)})"
    }

    fun permissionPrompt(event: AgentEvent.PermissionAsk): String = buildString {
        append("Permiso para `${event.toolName}`")
        if (event.subject.isNotEmpty()) append(":\n    ").append(event.subject.lines().joinToString("\n    "))
    }

    fun tokens(n: Int): String = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

    fun formatMs(ms: Long): String = if (ms >= 1000) String.format("%.1fs", ms / 1000.0) else "${ms}ms"

    fun isDestructive(event: AgentEvent.PermissionAsk): Boolean =
        event.toolName == "bash" && DESTRUCTIVE.any { it.containsMatchIn(event.subject) }

    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)"""),
        Regex("""\bgit\s+(push\s+.*--force|reset\s+--hard|clean\s+-[a-z]*f|checkout\s+--\s)"""),
        Regex("""\b(mkfs|dd\s+if=|format\s+[a-z]:)"""),
        Regex("""\bRemove-Item\b.*-Recurse"""),
    )

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
}

/** Lo que un frontend le manda al core. Es deliberadamente poco: prompt, cancelar, responder a permisos. */
interface Session {
    val root: AgentContext
    /** Para expandir `/nombre args` y completar en la TUI. */
    val skills: SkillCatalog get() = SkillCatalog(emptyList())
    suspend fun turn(prompt: String)
    fun cancel()
}

fun parseReply(line: String?): PermissionReply = when (line?.trim()) {
    "s", "y", "si", "sí", "yes" -> PermissionReply.ALLOW
    "S", "Y", "always", "siempre" -> PermissionReply.ALLOW_SESSION
    else -> PermissionReply.DENY
}
