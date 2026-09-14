package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.EchoTool
import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.frontend.acp.Acp
import com.softbenur.kotycli.interceptors.Permissions
import com.softbenur.kotycli.interceptors.RulePolicy
import com.softbenur.kotycli.json
import com.softbenur.kotycli.core.PermissionMode
import com.softbenur.kotycli.testContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.Writer
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El frontend ACP de punta a punta: un cliente de mentira escribe JSON-RPC por un pipe y lee lo que
 * contesta el agente. Nada de red ni de terminal.
 */
class AcpTest {
    @Test
    fun `initialize y session new abren la conversacion`() = withAgent(FakeProvider(mutableListOf())) { client ->
        val init = client.call(1, "initialize", """{"protocolVersion":1}""").response
        assertEquals(1, init["result"]!!.jsonObject["protocolVersion"]?.jsonPrimitive?.int())
        assertNotNull(client.newSession())
    }

    @Test
    fun `un turn llega como agent_message_chunk y termina en end_turn`() =
        withAgent(FakeProvider(mutableListOf(FakeProvider.text("hecho")))) { client ->
            val id = client.newSession()
            val turn = client.call(3, "session/prompt", """{"sessionId":"$id","prompt":[{"type":"text","text":"hola"}]}""")

            assertEquals("end_turn", turn.response["result"]!!.jsonObject["stopReason"]?.jsonPrimitive?.content)
            val chunks = turn.updates.filter { it.update()?.get("sessionUpdate")?.jsonPrimitive?.content == "agent_message_chunk" }
            assertEquals("hecho", chunks.joinToString("") { it.update()!!["content"]!!.jsonObject["text"]!!.jsonPrimitive.content })
        }

    @Test
    fun `una tool call sale como tool_call y se cierra con tool_call_update`() =
        withAgent(
            FakeProvider(mutableListOf(
                FakeProvider.toolCall("call-1", "echo", json("text" to "hola")),
                FakeProvider.text("listo"),
            ))
        ) { client ->
            val id = client.newSession()
            val turn = client.call(3, "session/prompt", """{"sessionId":"$id","prompt":[{"type":"text","text":"usa echo"}]}""")

            val start = turn.updates.mapNotNull { it.update() }.first { it["sessionUpdate"]?.jsonPrimitive?.content == "tool_call" }
            assertEquals("call-1", start["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("in_progress", start["status"]?.jsonPrimitive?.content)

            val end = turn.updates.mapNotNull { it.update() }.first { it["sessionUpdate"]?.jsonPrimitive?.content == "tool_call_update" }
            assertEquals("completed", end["status"]?.jsonPrimitive?.content)
            assertTrue(end["content"].toString().contains("eco: hola"))
        }

    @Test
    fun `un Ask se convierte en session request_permission y la respuesta del cliente lo resuelve`() {
        val provider = FakeProvider(mutableListOf(
            FakeProvider.toolCall("call-1", "escribe", json("text" to "x")),
            FakeProvider.text("listo"),
        ))
        // `escribe` no es readOnly, así que en modo `default` hay que pedir permiso.
        val ctx = testContext(
            provider,
            tools = listOf(EchoTool(name = "escribe", readOnly = false)),
            interceptors = listOf(Permissions(RulePolicy())),
            mode = PermissionMode.DEFAULT,
        )
        withAgent(LoopSession(ctx)) { client ->
            val id = client.newSession()
            val turn = client.call(
                3,
                "session/prompt",
                """{"sessionId":"$id","prompt":[{"type":"text","text":"escribe algo"}]}""",
                onRequest = { ask ->
                    assertEquals("session/request_permission", ask["method"]?.jsonPrimitive?.content)
                    val params = ask["params"]!!.jsonObject
                    assertEquals("call-1", params["toolCall"]!!.jsonObject["toolCallId"]?.jsonPrimitive?.content)
                    """{"outcome":{"outcome":"selected","optionId":"allow-once"}}"""
                },
            )

            assertEquals("end_turn", turn.response["result"]!!.jsonObject["stopReason"]?.jsonPrimitive?.content)
            // La tool call se anuncia como `pending` antes de pedir el permiso y acaba completada.
            val states = turn.updates.mapNotNull { it.update() }
                .filter { it["toolCallId"]?.jsonPrimitive?.content == "call-1" }
                .mapNotNull { it["status"]?.jsonPrimitive?.content }
            assertEquals(listOf("pending", "in_progress", "completed"), states)
        }
    }

    @Test
    fun `denegar el permiso cierra la tool call como failed`() {
        val provider = FakeProvider(mutableListOf(
            FakeProvider.toolCall("call-1", "escribe", json("text" to "x")),
            FakeProvider.text("no pude"),
        ))
        val ctx = testContext(
            provider,
            tools = listOf(EchoTool(name = "escribe", readOnly = false)),
            interceptors = listOf(Permissions(RulePolicy())),
            mode = PermissionMode.DEFAULT,
        )
        withAgent(LoopSession(ctx)) { client ->
            val id = client.newSession()
            val turn = client.call(
                3,
                "session/prompt",
                """{"sessionId":"$id","prompt":[{"type":"text","text":"escribe algo"}]}""",
                onRequest = { """{"outcome":{"outcome":"selected","optionId":"reject-once"}}""" },
            )

            assertEquals("end_turn", turn.response["result"]!!.jsonObject["stopReason"]?.jsonPrimitive?.content)
            val states = turn.updates.mapNotNull { it.update() }
                .filter { it["toolCallId"]?.jsonPrimitive?.content == "call-1" }
                .mapNotNull { it["status"]?.jsonPrimitive?.content }
            assertEquals(listOf("pending", "failed"), states)
        }
    }

    @Test
    fun `session cancel corta el turn y responde cancelled`() {
        val provider = FakeProvider(mutableListOf(FakeProvider.toolCall("call-1", "lento", json("text" to "x"))))
        val ctx = testContext(provider, tools = listOf(EchoTool(name = "lento", block = true)))
        withAgent(LoopSession(ctx)) { client ->
            val id = client.newSession()
            val turn = client.call(
                3,
                "session/prompt",
                """{"sessionId":"$id","prompt":[{"type":"text","text":"tarda"}]}""",
                onUpdate = { message ->
                    // En cuanto la tool arranca (y se queda colgada), el cliente cancela.
                    if (message.update()?.get("sessionUpdate")?.jsonPrimitive?.content == "tool_call") {
                        client.notify("session/cancel", """{"sessionId":"$id"}""")
                    }
                },
            )
            assertEquals("cancelled", turn.response["result"]!!.jsonObject["stopReason"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `un fallo del proveedor vuelve como error JSON-RPC`() =
        withAgent(FakeProvider(mutableListOf(FakeProvider.failing("502 del gateway")))) { client ->
            val id = client.newSession()
            val turn = client.call(3, "session/prompt", """{"sessionId":"$id","prompt":[{"type":"text","text":"hola"}]}""")
            val error = turn.response["error"]?.jsonObject
            assertNotNull(error)
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("502"))
        }

    @Test
    fun `un metodo que no implementamos devuelve method not found`() = withAgent(FakeProvider(mutableListOf())) { client ->
        val error = client.call(1, "session/load", """{"sessionId":"x"}""").response["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.int())
    }

    @Test
    fun `una sesion desconocida es un error de parametros`() = withAgent(FakeProvider(mutableListOf())) { client ->
        val error = client.call(1, "session/prompt", """{"sessionId":"nope","prompt":[{"type":"text","text":"hola"}]}""")
            .response["error"]!!.jsonObject
        assertEquals(-32602, error["code"]!!.jsonPrimitive.int())
    }
}

// --- andamiaje ---

private fun withAgent(provider: FakeProvider, body: suspend (AcpClient) -> Unit) =
    withAgent(LoopSession(testContext(provider)), body)

private fun withAgent(session: Session, body: suspend (AcpClient) -> Unit) = runBlocking {
    val pipes = Pipes()
    val agent = Acp(
        open = { session },
        input = pipes.agentIn,
        output = pipes.agentOut,
        defaultCwd = session.root.env.workDir,
    )
    // Fuera del scope del test: si el agente se quedase leyendo, el test falla por timeout en vez de colgarse.
    val agentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val job = agentScope.launch { agent.run() }
    val client = AcpClient(pipes, session.root.env.workDir)
    try {
        body(client)
    } finally {
        runCatching { pipes.clientOut.close() } // EOF: el bucle de lectura del agente termina
        withTimeoutOrNull(5_000) { job.join() }
        agentScope.cancel()
        client.close()
        pipes.close()
    }
}

private class Pipes {
    private val toAgent = PipedOutputStream()
    private val fromAgent = PipedOutputStream()
    val agentIn: BufferedReader = BufferedReader(InputStreamReader(PipedInputStream(toAgent, 1 shl 16), Charsets.UTF_8))
    val agentOut: Writer = OutputStreamWriter(fromAgent, Charsets.UTF_8)
    val clientIn: BufferedReader = BufferedReader(InputStreamReader(PipedInputStream(fromAgent, 1 shl 16), Charsets.UTF_8))
    val clientOut: Writer = OutputStreamWriter(toAgent, Charsets.UTF_8)

    fun close() {
        runCatching { clientOut.close() }
        runCatching { agentOut.close() }
    }
}

private class Exchange(val response: JsonObject, val updates: List<JsonObject>)

/** Cliente ACP de mentira: manda peticiones y junta las notificaciones que llegan antes de la respuesta. */
private class AcpClient(private val pipes: Pipes, val cwd: Path) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val incoming = LinkedBlockingQueue<String>()

    /** Un hilo propio leyendo: así `read()` puede rendirse por timeout en vez de bloquear el test. */
    private val reader = Thread {
        runCatching { while (true) incoming.put(pipes.clientIn.readLine() ?: break) }
        incoming.put(EOF)
    }.apply { isDaemon = true; start() }

    fun close() = reader.interrupt()

    suspend fun newSession(): String =
        call(2, "session/new", """{"cwd":${JsonPrimitive(cwd.toString())},"mcpServers":[]}""")
            .response["result"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content

    /**
     * Manda una petición y lee hasta su respuesta. Las peticiones que llegan del agente por el camino
     * (los permisos) las contesta `onRequest`.
     */
    suspend fun call(
        id: Int,
        method: String,
        params: String,
        onRequest: (JsonObject) -> String = { "{}" },
        onUpdate: suspend (JsonObject) -> Unit = {},
    ): Exchange {
        notify(method, params, id)
        val updates = mutableListOf<JsonObject>()
        while (true) {
            val message = read()
            val incomingMethod = message["method"]?.jsonPrimitive?.content
            when {
                incomingMethod == null && message["id"]?.jsonPrimitive?.int() == id -> return Exchange(message, updates)
                incomingMethod != null && message["id"] != null ->
                    send("""{"jsonrpc":"2.0","id":${message["id"]!!.jsonPrimitive.int()},"result":${onRequest(message)}}""")
                else -> {
                    updates += message
                    onUpdate(message)
                }
            }
        }
    }

    suspend fun notify(method: String, params: String, id: Int? = null) =
        send("""{"jsonrpc":"2.0",${id?.let { "\"id\":$it," } ?: ""}"method":"$method","params":$params}""")

    private suspend fun send(line: String) = withContext(Dispatchers.IO) {
        pipes.clientOut.write(line)
        pipes.clientOut.write("\n")
        pipes.clientOut.flush()
    }

    private suspend fun read(): JsonObject = withContext(Dispatchers.IO) {
        val line = incoming.poll(20, TimeUnit.SECONDS) ?: error("el agente no ha contestado en 20 s")
        if (line === EOF) error("el agente ha cerrado la salida")
        json.parseToJsonElement(line).jsonObject
    }

    private companion object {
        /** Centinela de fin de salida; se compara por identidad, no por contenido. */
        val EOF = StringBuilder("(eof)").toString()
    }
}

/** `params.update` de un `session/update`; `null` si el mensaje era otra cosa. */
private fun JsonObject.update(): JsonObject? =
    if (this["method"]?.jsonPrimitive?.content != "session/update") null
    else this["params"]?.jsonObject?.get("update")?.jsonObject

private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()
