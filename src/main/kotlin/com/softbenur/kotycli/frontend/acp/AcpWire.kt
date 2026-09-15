package com.softbenur.kotycli.frontend.acp

import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.frontend.Render
import com.softbenur.kotycli.skills.Skill
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

/** Versión del protocolo que hablamos. Se negocia en `initialize`: si el cliente pide menos, se le da la suya. */
const val ACP_PROTOCOL_VERSION = 1

/**
 * Traducción pura entre nuestros eventos y el wire de ACP. Sin I/O ni estado, igual que `OpenAiWire`:
 * así se puede testear el mapeo sin levantar el protocolo.
 */
object AcpWire {
    /** `initialize`: qué sabemos hacer. No cargamos sesiones de disco y solo entendemos prompts de texto. */
    fun initializeResult(clientVersion: Int): JsonObject = buildJsonObject {
        put("protocolVersion", minOf(clientVersion, ACP_PROTOCOL_VERSION))
        putJsonObject("agentCapabilities") {
            put("loadSession", false)
            putJsonObject("promptCapabilities") {
                put("image", false)
                put("audio", false)
                put("embeddedContext", false)
            }
        }
        putJsonArray("authMethods") {}
    }

    /**
     * El texto de un `session/prompt`. ACP manda una lista de bloques; nosotros entendemos `text` y,
     * de los demás, la referencia al recurso, que al modelo le sirve para ir a leerlo con `read`.
     */
    fun promptText(params: JsonObject): String {
        val blocks = (params["prompt"] as? JsonArray) ?: return ""
        return blocks.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                "resource_link" -> obj["uri"]?.jsonPrimitive?.contentOrNull
                "resource" -> (obj["resource"] as? JsonObject)?.let { it["text"]?.jsonPrimitive?.contentOrNull ?: it["uri"]?.jsonPrimitive?.contentOrNull }
                else -> null
            }
        }.joinToString("\n").trim()
    }

    /** Tipos de tool call de ACP. El cliente los usa para el icono y para agrupar. */
    fun toolKind(name: String): String = when (name) {
        "bash" -> "execute"
        "read" -> "read"
        "edit", "create" -> "edit"
        "fetch" -> "fetch"
        else -> "other"
    }

    fun title(name: String, input: JsonObject): String = Render.toolTitle(name, input, max = 120)

    /** `session/update` con un trozo de respuesta del modelo. */
    fun agentMessageChunk(text: String): JsonObject = update("agent_message_chunk") {
        put("content", textContent(text))
    }

    /** Lo que no es respuesta directa del agente raíz (la actividad de un subagente) va como pensamiento. */
    fun thoughtChunk(text: String): JsonObject = update("agent_thought_chunk") {
        put("content", textContent(text))
    }

    /**
     * Alta de una tool call. `status` es `pending` cuando está esperando permiso e `in_progress`
     * cuando ya se está ejecutando.
     */
    fun toolCall(callId: String, name: String, input: JsonObject, status: String, workDir: Path): JsonObject =
        update("tool_call") {
            put("toolCallId", callId)
            put("title", title(name, input))
            put("kind", toolKind(name))
            put("status", status)
            put("rawInput", input)
            locations(name, input, workDir)?.let { put("locations", it) }
            diff(name, input, workDir)?.let { put("content", buildJsonArray { add(it) }) }
        }

    /** Cambio de estado de una tool call ya dada de alta. */
    fun toolCallStatus(callId: String, status: String): JsonObject = update("tool_call_update") {
        put("toolCallId", callId)
        put("status", status)
    }

    /** Cierre de una tool call con su salida. El resultado ya viene recortado por el interceptor `Truncate`. */
    fun toolCallEnd(event: AgentEvent.ToolEnd): JsonObject = update("tool_call_update") {
        put("toolCallId", event.call.id)
        put("status", if (event.result.isError) "failed" else "completed")
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "content")
                put("content", textContent(event.result.content))
            })
        }
    }

    /** Los skills, como comandos `/` del cliente. Mismo catálogo que completa la TUI con Tab. */
    fun availableCommands(skills: List<Skill>): JsonObject = update("available_commands_update") {
        putJsonArray("availableCommands") {
            skills.forEach { skill ->
                add(buildJsonObject {
                    put("name", skill.name)
                    put("description", skill.description.lineSequence().first().take(200))
                })
            }
        }
    }

    fun sessionNotification(sessionId: String, update: JsonObject): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("update", update)
    }

    /** `session/request_permission`: la tool call que espera y las tres opciones que sabemos resolver. */
    fun requestPermission(sessionId: String, event: AgentEvent.PermissionAsk, workDir: Path): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putJsonObject("toolCall") {
            put("toolCallId", event.callId)
            put("title", title(event.toolName, event.input))
            put("kind", toolKind(event.toolName))
            put("status", "pending")
            put("rawInput", event.input)
            locations(event.toolName, event.input, workDir)?.let { put("locations", it) }
        }
        putJsonArray("options") {
            add(option(ALLOW_ONCE, "Permitir", "allow_once"))
            add(option(ALLOW_ALWAYS, "Permitir siempre en esta sesión", "allow_always"))
            add(option(REJECT_ONCE, "Denegar", "reject_once"))
        }
    }

    /** La respuesta del cliente. Lo que no entendemos, y la cancelación, se resuelven como `Deny`. */
    fun permissionReply(outcome: JsonElement): PermissionReply {
        val result = (outcome as? JsonObject)?.get("outcome") as? JsonObject ?: return PermissionReply.DENY
        if (result["outcome"]?.jsonPrimitive?.contentOrNull != "selected") return PermissionReply.DENY
        return when (result["optionId"]?.jsonPrimitive?.contentOrNull) {
            ALLOW_ONCE -> PermissionReply.ALLOW
            ALLOW_ALWAYS -> PermissionReply.ALLOW_SESSION
            else -> PermissionReply.DENY
        }
    }

    /** `stopReason` de ACP para un `BudgetExceeded`, que en nuestro loop puede ser por tokens o por iteraciones. */
    fun budgetStopReason(what: String): String = if (what.startsWith("tokens")) "max_tokens" else "max_turn_requests"

    /** Resumen de una línea de lo que hace un subagente, que ACP no modela. */
    fun subagentLine(event: AgentEvent): String? = when (event) {
        is AgentEvent.SubagentStart -> "● task ${event.agentType} «${event.prompt.lineSequence().first().take(80)}»"
        is AgentEvent.SubagentEnd -> "  ✓ ${Render.tokens(event.tokensBurned)} tokens quemados · ${event.tokensReturned} caracteres devueltos"
        is AgentEvent.ToolStart -> "│ " + Render.toolLine(event).removePrefix("● ")
        is AgentEvent.ToolEnd -> "│" + Render.resultLine(event)
        is AgentEvent.Failed -> "│ error del proveedor: ${event.message}"
        else -> null
    }

    const val ALLOW_ONCE = "allow-once"
    const val ALLOW_ALWAYS = "allow-always"
    const val REJECT_ONCE = "reject-once"

    private fun option(id: String, name: String, kind: String) = buildJsonObject {
        put("optionId", id)
        put("name", name)
        put("kind", kind)
    }

    private fun textContent(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** ACP exige rutas absolutas; las de las tools son relativas al working dir. */
    private fun locations(name: String, input: JsonObject, workDir: Path): JsonArray? {
        if (name != "read" && name != "edit" && name != "create") return null
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return null
        return buildJsonArray {
            add(buildJsonObject {
                put("path", workDir.resolve(path).toAbsolutePath().normalize().toString())
                input["offset"]?.jsonPrimitive?.intOrNull?.let { put("line", it) }
            })
        }
    }

    /** Un `edit` o un `create` se enseñan como diff: el cliente lo pinta antes de que el usuario dé permiso. */
    private fun diff(name: String, input: JsonObject, workDir: Path): JsonObject? {
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val absolute = workDir.resolve(path).toAbsolutePath().normalize().toString()
        return when (name) {
            "create" -> buildJsonObject {
                put("type", "diff")
                put("path", absolute)
                put("newText", input["content"]?.jsonPrimitive?.contentOrNull ?: return null)
            }
            "edit" -> buildJsonObject {
                put("type", "diff")
                put("path", absolute)
                put("oldText", input["old_string"]?.jsonPrimitive?.contentOrNull ?: return null)
                put("newText", input["new_string"]?.jsonPrimitive?.contentOrNull ?: return null)
            }
            else -> null
        }
    }

    private fun update(kind: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("sessionUpdate", kind)
        build()
    }
}
