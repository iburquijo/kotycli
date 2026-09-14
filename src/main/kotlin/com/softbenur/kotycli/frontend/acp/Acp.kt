package com.softbenur.kotycli.frontend.acp

import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.frontend.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Writer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lado agente del Agent Client Protocol por stdio (ADR 0010): JSON-RPC 2.0, un mensaje por línea.
 * Los clientes los mantienen otros (agent-shell y agent-ide en Emacs, Zed, Neovim); aquí solo se
 * traduce el `Flow<AgentEvent>` que ya consume la TUI a `session/update`.
 *
 * **stdout es del protocolo**: esta clase no imprime nada, escribe en el `Writer` que se le da y
 * manda los errores al `log`.
 */
class Acp(
    /** Una sesión nueva por cada `session/new`, con el working dir que pida el cliente. */
    private val open: (Path) -> Session,
    input: BufferedReader,
    output: Writer,
    private val defaultCwd: Path,
    private val log: (String) -> Unit = {},
    /** Un `Ask` sin respuesta del cliente se resuelve como `Deny`: el loop no se queda colgado. */
    private val permissionTimeout: Duration = 5.minutes,
) {
    private val peer = JsonRpcPeer(input, output, log)
    private val sessions = ConcurrentHashMap<String, AcpSession>()

    /** Atiende hasta que el cliente cierre stdin. */
    suspend fun run() = coroutineScope {
        // Las bombas de eventos viven fuera del scope del bucle de lectura: si no, `serve` nunca terminaría.
        val pumps = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            peer.serve { method, params -> handle(pumps, method, params) }
        } finally {
            pumps.cancel()
        }
    }

    private suspend fun handle(pumps: CoroutineScope, method: String, params: JsonObject): JsonElement? = when (method) {
        "initialize" -> AcpWire.initializeResult(params["protocolVersion"]?.jsonPrimitive?.intOrNull ?: ACP_PROTOCOL_VERSION)
        // No hay métodos de autenticación: la clave sale de la config del proveedor, como en la TUI.
        "authenticate" -> JsonObject(emptyMap())
        "session/new" -> newSession(pumps, params)
        "session/prompt" -> prompt(params)
        "session/cancel" -> cancel(params)
        else -> throw RpcError(RpcError.METHOD_NOT_FOUND, "kotycli no implementa $method")
    }

    private suspend fun newSession(pumps: CoroutineScope, params: JsonObject): JsonObject {
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
            ?.let { Path.of(it) } ?: defaultCwd
        val session = try {
            open(cwd.toAbsolutePath().normalize())
        } catch (e: Exception) {
            throw RpcError(RpcError.INTERNAL_ERROR, "no se pudo abrir la sesión en $cwd: ${e.message}")
        }
        val acp = AcpSession(UUID.randomUUID().toString(), session)
        acp.pump = pumps.launch {
            session.root.events.collect { event -> render(acp, event, this) }
        }
        // El interceptor de permisos deniega si no hay nadie suscrito: no contestamos hasta que la bomba esté.
        session.root.events.subscriptionCount.first { it > 0 }
        sessions[acp.id] = acp

        if (session.skills.all.isNotEmpty()) {
            peer.notify("session/update", AcpWire.sessionNotification(acp.id, AcpWire.availableCommands(session.skills.all)))
        }
        return buildJsonObject { put("sessionId", acp.id) }
    }

    private suspend fun prompt(params: JsonObject): JsonObject {
        val acp = session(params)
        val text = AcpWire.promptText(params)
        if (text.isEmpty()) return buildJsonObject { put("stopReason", "end_turn") }

        return acp.turnLock.withLock {
            acp.startTurn()
            try {
                acp.session.turn(acp.session.skills.expand(text) ?: text)
            } catch (e: CancellationException) {
                // `session/cancel` cancela el job del turn; solo relanzamos si nos han cancelado a nosotros.
                if (!acp.cancelled) throw e
            }
            // Que la respuesta no adelante a los `session/update` que la bomba aún tiene en la mano.
            withTimeoutOrNull(DRAIN_TIMEOUT) { acp.drained.await() }
            acp.failure?.let { throw RpcError(RpcError.INTERNAL_ERROR, it) }
            buildJsonObject { put("stopReason", acp.stopReason()) }
        }
    }

    /** `session/cancel` es una notificación: no devuelve nada y no puede bloquear. */
    private fun cancel(params: JsonObject): JsonElement? {
        val acp = sessions[params["sessionId"]?.jsonPrimitive?.contentOrNull] ?: return null
        acp.cancelled = true
        acp.session.cancel()
        return null
    }

    private fun session(params: JsonObject): AcpSession {
        val id = params["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: throw RpcError(RpcError.INVALID_PARAMS, "falta sessionId")
        return sessions[id] ?: throw RpcError(RpcError.INVALID_PARAMS, "sesión desconocida: $id")
    }

    /** El mismo `Flow<AgentEvent>` que pinta la TUI, traducido a `session/update` (doc 08). */
    private suspend fun render(acp: AcpSession, event: AgentEvent, scope: CoroutineScope) {
        val workDir = acp.session.root.env.workDir
        if (event.agentId != acp.session.root.id) {
            // ACP no modela subagentes: su actividad va como pensamiento y el cliente decide si la agrupa.
            if (event is AgentEvent.PermissionAsk) scope.launch { event.reply.complete(askPermission(acp, event)) }
            else AcpWire.subagentLine(event)?.let { update(acp, AcpWire.thoughtChunk(it)) }
            return
        }
        when (event) {
            is AgentEvent.TextDelta -> update(acp, AcpWire.agentMessageChunk(event.text))

            is AgentEvent.ToolStart -> update(
                acp,
                if (acp.announced.add(event.call.id)) AcpWire.toolCall(event.call.id, event.call.name, event.call.input, "in_progress", workDir)
                else AcpWire.toolCallStatus(event.call.id, "in_progress"),
            )

            is AgentEvent.ToolEnd -> update(acp, AcpWire.toolCallEnd(event))

            // La petición va en su propia corrutina: mientras el usuario decide, los demás eventos siguen fluyendo.
            is AgentEvent.PermissionAsk -> scope.launch {
                if (acp.announced.add(event.callId)) {
                    update(acp, AcpWire.toolCall(event.callId, event.toolName, event.input, "pending", workDir))
                }
                val reply = askPermission(acp, event)
                // Si se deniega no habrá `ToolStart` ni `ToolEnd`: hay que cerrar la tool call aquí.
                if (reply == PermissionReply.DENY) update(acp, AcpWire.toolCallStatus(event.callId, "failed"))
                event.reply.complete(reply)
            }

            is AgentEvent.Refusal -> acp.stop = "refusal"
            is AgentEvent.BudgetExceeded -> acp.stop = AcpWire.budgetStopReason(event.what)
            is AgentEvent.Failed -> acp.failure = event.message
            is AgentEvent.TurnEnd -> acp.drained.complete(Unit)
            // `SubagentStart` y `SubagentEnd` llegan con el id del hijo, así que los atiende la rama de arriba.
            is AgentEvent.SubagentStart, is AgentEvent.SubagentEnd,
            is AgentEvent.AssistantMessage, is AgentEvent.Compacted, is AgentEvent.UsageUpdate -> {}
        }
    }

    private suspend fun askPermission(acp: AcpSession, event: AgentEvent.PermissionAsk): PermissionReply {
        val params = AcpWire.requestPermission(acp.id, event, acp.session.root.env.workDir)
        val outcome = try {
            withTimeoutOrNull(permissionTimeout) { peer.request("session/request_permission", params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("session/request_permission: ${e.message}")
            null
        }
        if (outcome == null) {
            log("permiso sin respuesta para ${event.toolName}: denegado")
            return PermissionReply.DENY
        }
        return AcpWire.permissionReply(outcome)
    }

    private suspend fun update(acp: AcpSession, update: JsonObject) =
        peer.notify("session/update", AcpWire.sessionNotification(acp.id, update))

    private companion object {
        val DRAIN_TIMEOUT = 5.seconds
    }
}

/** Una conversación del cliente. El estado del turn en curso es lo único mutable. */
private class AcpSession(val id: String, val session: Session) {
    /** Tool calls ya anunciadas al cliente: la segunda vez se manda `tool_call_update`, no otro alta. */
    val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val turnLock = Mutex()
    lateinit var pump: Job

    @Volatile var cancelled = false

    /** `stopReason` que ha decidido un evento (rechazo o presupuesto); si nadie lo toca, `end_turn`. */
    @Volatile var stop: String? = null

    /** Un fallo del proveedor se devuelve como error JSON-RPC, no como un turn que termina bien. */
    @Volatile var failure: String? = null

    /** Se completa cuando la bomba ha procesado el `TurnEnd`: hasta entonces la respuesta espera. */
    @Volatile var drained = CompletableDeferred<Unit>()

    fun startTurn() {
        cancelled = false
        stop = null
        failure = null
        drained = CompletableDeferred()
        announced.clear()
    }

    fun stopReason(): String = if (cancelled) "cancelled" else stop ?: "end_turn"
}
