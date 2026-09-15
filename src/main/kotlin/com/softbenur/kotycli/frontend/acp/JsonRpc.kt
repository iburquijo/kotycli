package com.softbenur.kotycli.frontend.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Error con código JSON-RPC. Lo que lance un handler se convierte en el `error` de la respuesta. */
class RpcError(val code: Int, override val message: String) : Exception(message) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** Atiende un método entrante. Devuelve el `result`, o `null` si el método era una notificación. */
fun interface RpcHandler {
    suspend fun handle(method: String, params: JsonObject): JsonElement?
}

/**
 * JSON-RPC 2.0 sobre líneas: un mensaje JSON por línea, sin cabeceras. Es lo que habla ACP por stdio.
 * Los dos sentidos a la vez: atendemos las peticiones del cliente y le mandamos las nuestras (permisos).
 */
class JsonRpcPeer(
    private val input: BufferedReader,
    private val output: Writer,
    /** Adónde van los errores de protocolo: en ACP stdout es del protocolo y no se puede imprimir nada (ADR 0010). */
    private val log: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)

    /**
     * Lee hasta EOF. Cada petición se atiende en su propia corrutina: un `session/prompt` de diez minutos
     * no puede tapar el `session/cancel` que viene detrás.
     */
    suspend fun serve(handler: RpcHandler) {
        try {
            coroutineScope {
                while (true) {
                    val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
                    if (line.isBlank()) continue
                    val message = try {
                        json.parseToJsonElement(line).jsonObject
                    } catch (e: Exception) {
                        log("línea ilegible, descartada: ${line.take(200)}")
                        continue
                    }
                    if (message["method"] != null) launch { dispatch(message, handler) } else settle(message)
                }
            }
        } finally {
            val gone = RpcError(RpcError.INTERNAL_ERROR, "el cliente ha cerrado la conexión")
            pending.values.forEach { it.completeExceptionally(gone) }
            pending.clear()
        }
    }

    /** Petición nuestra al cliente (hoy solo `session/request_permission`). Suspende hasta su respuesta. */
    suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextId.getAndIncrement()
        val waiting = CompletableDeferred<JsonElement>()
        pending[id] = waiting
        return try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            waiting.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JsonObject) = send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    })

    private suspend fun dispatch(message: JsonObject, handler: RpcHandler) {
        val id = message["id"]?.takeIf { it !is JsonNull }
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        try {
            val result = handler.handle(method, params)
            if (id != null) send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result ?: JsonObject(emptyMap()))
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = e.message ?: e::class.simpleName ?: "error"
            log("$method: $text")
            if (id != null) send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", (e as? RpcError)?.code ?: RpcError.INTERNAL_ERROR)
                    put("message", text)
                }
            })
        }
    }

    /** Respuesta a una petición nuestra. Los ids que no reconocemos se ignoran, como manda JSON-RPC. */
    private fun settle(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.longOrNull ?: return
        val waiting = pending.remove(id) ?: return
        val error = message["error"] as? JsonObject
        if (error == null) {
            waiting.complete(message["result"] ?: JsonObject(emptyMap()))
        } else {
            waiting.completeExceptionally(
                RpcError(
                    error["code"]?.jsonPrimitive?.intOrNull ?: RpcError.INTERNAL_ERROR,
                    error["message"]?.jsonPrimitive?.contentOrNull ?: "error sin mensaje",
                )
            )
        }
    }

    private suspend fun send(message: JsonObject) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            // Un mensaje por línea: `encodeToString` nunca mete saltos, los escapa.
            output.write(json.encodeToString(JsonObject.serializer(), message))
            output.write("\n")
            output.flush()
        }
    }
}
