package dev.kotycli.providers.openai

import dev.kotycli.core.Block
import dev.kotycli.core.Message
import dev.kotycli.core.Role
import dev.kotycli.core.StopReason
import dev.kotycli.core.ToolDefinition
import dev.kotycli.core.Usage
import dev.kotycli.providers.Capabilities
import dev.kotycli.providers.CompletionAccumulator
import dev.kotycli.providers.Provider
import dev.kotycli.providers.ProviderException
import dev.kotycli.providers.Request
import dev.kotycli.providers.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Cabeceras de autenticación por petición. Clave de API hoy; el exchange de Copilot mañana. */
fun interface Authenticator {
    suspend fun headers(): Map<String, String>

    companion object {
        val NONE = Authenticator { emptyMap() }
        fun bearer(token: String) = Authenticator { mapOf("Authorization" to "Bearer $token") }
    }
}

/**
 * Adaptador OpenAI-compatible: `POST {baseUrl}/chat/completions` con `tools` y `stream: true`.
 * Sin SDK: el `HttpClient` común y `kotlinx.serialization`. Ningún JSON con forma de proveedor sale de este paquete.
 */
class OpenAiProvider(
    private val http: HttpClient,
    private val baseUrl: String,
    private val authenticator: Authenticator = Authenticator.NONE,
    private val extraHeaders: Map<String, String> = emptyMap(),
    override val id: String = "openai",
    override val capabilities: Capabilities = Capabilities(),
    private val requestTimeout: Duration = Duration.ofMinutes(10),
) : Provider {
    private val wire = OpenAiWire(id)

    override fun stream(request: Request): Flow<StreamEvent> = flow {
        val body = wire.toWire(request).toString()
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        (authenticator.headers() + extraHeaders).forEach { (k, v) -> builder.header(k, v) }
        val httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()

        val response = try {
            http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (e: java.io.IOException) {
            throw ProviderException("No se pudo conectar con $baseUrl: ${e.message}", e)
        }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) {
                val text = stream.readAllBytes().toString(Charsets.UTF_8)
                throw ProviderException("HTTP ${response.statusCode()} de $baseUrl: ${text.take(2000)}")
            }
            val contentType = response.headers().firstValue("content-type").orElse("")
            if (!contentType.contains("event-stream")) {
                // Algunos gateways ignoran `stream: true` y devuelven la respuesta entera.
                val json = Json.parseToJsonElement(stream.readAllBytes().toString(Charsets.UTF_8)).jsonObject
                emit(StreamEvent.Done(wire.fromWireNonStreaming(json)))
                return@use
            }
            wire.readSse(stream) { emit(it) }
        }
    }.flowOn(Dispatchers.IO)
}

/** Traducción pura entre nuestro modelo neutral y el wire OpenAI. Sin red, para poder testearla. */
class OpenAiWire(private val providerId: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toWire(request: Request): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        put("max_tokens", request.maxTokens)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", request.system) })
            request.messages.forEach { m -> toWireMessages(m).forEach { add(it) } }
        })
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray { request.tools.forEach { add(toolDef(it)) } })
    }

    private fun toolDef(t: ToolDefinition) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", t.inputSchema)
        }
    }

    /** Un mensaje neutral puede ser N mensajes de wire: cada `ToolResult` va como `role: tool` individual. */
    fun toWireMessages(m: Message): List<JsonObject> = when (m.role) {
        Role.USER -> {
            val out = mutableListOf<JsonObject>()
            m.content.filterIsInstance<Block.ToolResult>().forEach { r ->
                out += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", r.toolUseId)
                    put("content", if (r.isError && !r.content.startsWith("Error")) "Error: ${r.content}" else r.content)
                }
            }
            val text = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
            if (text.isNotEmpty()) out += buildJsonObject { put("role", "user"); put("content", text) }
            out
        }
        Role.ASSISTANT -> {
            val text = m.content.filterIsInstance<Block.Text>().joinToString("") { it.text }
            val calls = m.content.filterIsInstance<Block.ToolUse>()
            listOf(buildJsonObject {
                put("role", "assistant")
                if (text.isNotEmpty() || calls.isEmpty()) put("content", text) else put("content", JsonNull)
                if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    calls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            putJsonObject("function") { put("name", c.name); put("arguments", c.input.toString()) }
                        })
                    }
                })
                m.content.filterIsInstance<Block.Opaque>().filter { it.providerId == providerId }.forEach { o ->
                    (o.payload as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                }
            })
        }
    }

    /** Lee el SSE línea a línea y acumula deltas por `index` hasta `finish_reason`. */
    suspend fun readSse(stream: InputStream, emit: suspend (StreamEvent) -> Unit) {
        val acc = CompletionAccumulator()
        val idToIndex = mutableMapOf<Int, String>()
        var stop: StopReason? = null
        var usage = Usage()
        val reasoning = StringBuilder()
        val reader = BufferedReader(stream.reader(Charsets.UTF_8))
        val data = StringBuilder()

        suspend fun handleEvent(payload: String) {
            if (payload.trim() == "[DONE]") return
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            obj["error"]?.let { throw ProviderException("Error del proveedor: $it") }
            obj["usage"]?.takeIf { it !is JsonNull }?.jsonObject?.let { usage = parseUsage(it) }
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            choice["delta"]?.takeIf { it !is JsonNull }?.jsonObject?.let { delta ->
                delta["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                    acc.text(it); emit(StreamEvent.TextDelta(it))
                }
                delta["reasoning_content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { reasoning.append(it) }
                delta["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray?.forEach { tc ->
                    val call = tc.jsonObject
                    val index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val fn = call["function"]?.takeIf { it !is JsonNull }?.jsonObject
                    val explicitId = call["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                    val id = idToIndex.getOrPut(index) { explicitId ?: "call_$index" }
                    fn?.get("name")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                        acc.toolStart(id, it); emit(StreamEvent.ToolUseStart(id, it))
                    }
                    fn?.get("arguments")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                        acc.toolArgs(id, it); emit(StreamEvent.ToolInputDelta(id, it))
                    }
                }
            }
            choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { stop = mapFinish(it) }
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> { if (data.isNotEmpty()) { handleEvent(data.toString()); data.setLength(0) } }
                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                else -> {} // comentarios `:` y campos `event:`/`id:` que no usamos
            }
        }
        if (data.isNotEmpty()) handleEvent(data.toString())

        if (reasoning.isNotEmpty()) acc.opaque(Block.Opaque(providerId, buildJsonObject { put("reasoning_content", reasoning.toString()) }))
        emit(StreamEvent.Done(acc.build(stop ?: if (acc.hasToolCalls()) StopReason.TOOL_USE else StopReason.END_TURN, usage)))
    }

    fun fromWireNonStreaming(obj: JsonObject): dev.kotycli.core.Completion {
        obj["error"]?.let { throw ProviderException("Error del proveedor: $it") }
        val acc = CompletionAccumulator()
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ProviderException("Respuesta sin choices: $obj")
        val message = choice["message"]?.jsonObject ?: throw ProviderException("Respuesta sin message: $obj")
        message["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { acc.text(it) }
        message["reasoning_content"]?.takeIf { it !is JsonNull }?.let { acc.opaque(Block.Opaque(providerId, buildJsonObject { put("reasoning_content", it) })) }
        message["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray?.forEachIndexed { i, tc ->
            val call = tc.jsonObject
            val id = call["id"]?.jsonPrimitive?.content ?: "call_$i"
            val fn = call["function"]?.jsonObject
            acc.toolStart(id, fn?.get("name")?.jsonPrimitive?.content.orEmpty())
            fn?.get("arguments")?.let { args -> acc.toolArgs(id, if (args is JsonPrimitive) args.content else args.toString()) }
        }
        val stop = choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { mapFinish(it) }
        val usage = obj["usage"]?.takeIf { it !is JsonNull }?.jsonObject?.let { parseUsage(it) } ?: Usage()
        return acc.build(stop ?: if (acc.hasToolCalls()) StopReason.TOOL_USE else StopReason.END_TURN, usage)
    }

    private fun parseUsage(u: JsonObject) = Usage(
        inputTokens = u["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        outputTokens = u["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
    )

    private fun mapFinish(reason: String): StopReason = when (reason) {
        "stop" -> StopReason.END_TURN
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }
}
