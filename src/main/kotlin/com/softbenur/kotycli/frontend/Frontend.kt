package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.CompactResult
import com.softbenur.kotycli.core.PermissionMode
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.core.runLoop
import com.softbenur.kotycli.skills.SkillCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Trozos que comparten la TUI y `--plain`: cómo resumir en una línea una tool call y su resultado. */
object Render {
    /** El campo del input que identifica la llamada para el usuario. Lo usan las tres frontends. */
    fun toolSubject(name: String, input: JsonObject): String = when (name) {
        "bash" -> input.str("description")?.let { "$it  ·  ${input.str("command").orEmpty()}" } ?: input.str("command").orEmpty()
        "read", "edit", "create" -> input.str("path").orEmpty()
        "fetch" -> input.str("url").orEmpty()
        "task" -> input.str("description") ?: input.str("prompt").orEmpty()
        else -> input.toString()
    }

    /** Una línea: `bash  compila y pasa los tests · ./gradlew build`. Sin adornos, para reutilizarla en ACP. */
    fun toolTitle(name: String, input: JsonObject, max: Int = 160): String {
        val subject = toolSubject(name, input).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (subject.isEmpty()) name else "$name  ${subject.take(max)}"
    }

    fun toolLine(event: AgentEvent.ToolStart): String = "● " + toolTitle(event.call.name, event.call.input)

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

    /** `/compact`: nivel 3 de la gestión de contexto. Lo hace el `ContextManager`, aquí solo se expone. */
    suspend fun compact(instructions: String? = null): CompactResult = root.contextManager.compact(root, instructions)

    /** `/config`: la configuración efectiva, en varias líneas, para verla sin salir de la sesión. */
    fun describe(): String = "modelo ${root.config.model} · permisos ${root.config.permissionMode.cli}"

    /** `/reload`: relee config, `AGENTS.md`, skills y roles sin tocar el historial. Devuelve qué ha pasado. */
    fun reload(): String = "Esta sesión no puede recargar la configuración."
}

/** Lo que `/reload` trae del disco. Lo construye quien sabe de ficheros (`Bootstrap`); la sesión solo lo aplica. */
data class Reload(val skills: SkillCatalog, val systemPrompt: String, val permissionMode: PermissionMode, val summary: String)

/** La `Session` que montan los tres frontends: un `runLoop` por turn, cancelable desde fuera. */
class LoopSession(
    override val root: AgentContext,
    skills: SkillCatalog = SkillCatalog(emptyList()),
    private val describer: () -> String = { "" },
    private val reloader: (() -> Reload)? = null,
) : Session {
    private var current: Job? = null

    override var skills: SkillCatalog = skills
        private set

    override suspend fun turn(prompt: String) = coroutineScope {
        val job = launch { runLoop(root, prompt) }
        current = job
        job.join()
    }

    override fun cancel() {
        current?.cancel()
    }

    override fun describe(): String = describer().ifBlank { super.describe() }

    override fun reload(): String {
        val reloader = reloader ?: return super.reload()
        val fresh = try {
            reloader()
        } catch (e: Exception) {
            return "No se ha recargado nada: ${e.message ?: e::class.simpleName}"
        }
        skills = fresh.skills
        root.config = root.config.copy(systemPrompt = fresh.systemPrompt, permissionMode = fresh.permissionMode)
        return fresh.summary
    }
}

fun parseReply(line: String?): PermissionReply = when (line?.trim()) {
    "s", "y", "si", "sí", "yes" -> PermissionReply.ALLOW
    "S", "Y", "always", "siempre" -> PermissionReply.ALLOW_SESSION
    else -> PermissionReply.DENY
}
