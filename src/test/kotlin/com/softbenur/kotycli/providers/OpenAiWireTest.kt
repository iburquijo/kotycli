package com.softbenur.kotycli.providers

import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.Message
import com.softbenur.kotycli.core.Role
import com.softbenur.kotycli.core.StopReason
import com.softbenur.kotycli.core.ToolDefinition
import com.softbenur.kotycli.json
import com.softbenur.kotycli.providers.openai.OpenAiWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiWireTest {
    private val wire = OpenAiWire("openai")

    @Test
    fun `toWire expande los tool results en mensajes role tool y reenvia opacos propios`() {
        val request = Request(
            model = "m", system = "sys", maxTokens = 100,
            tools = listOf(ToolDefinition("read", "lee", buildJsonObject { put("type", "object") })),
            messages = listOf(
                Message.user("hola"),
                Message(Role.ASSISTANT, listOf(
                    Block.ToolUse("c1", "read", json("path" to "a")),
                    Block.ToolUse("c2", "read", json("path" to "b")),
                    Block.Opaque("openai", buildJsonObject { put("reasoning_content", "pensando") }),
                    Block.Opaque("otro", buildJsonObject { put("x", "y") }),
                )),
                Message.toolResults(listOf(Block.ToolResult("c1", "A"), Block.ToolResult("c2", "fallo", isError = true))),
            ),
        )
        val body = wire.toWire(request)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
        val assistant = messages[2]
        assertEquals(2, assistant["tool_calls"]!!.jsonArray.size)
        assertEquals("""{"path":"a"}""", assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("pensando", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertTrue("x" !in assistant)
        assertEquals("c2", messages[4]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("Error: fallo", messages[4]["content"]!!.jsonPrimitive.content)
        assertEquals("read", body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readSse acumula texto y fragmentos de argumentos por index`() = runTest {
        val sse = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Voy "},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"a leer"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":""}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\": \"a.txt\"}"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"read","arguments":"{\"path\":\"b.txt\"}"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}

            data: [DONE]

        """.trimIndent()
        val events = mutableListOf<StreamEvent>()
        wire.readSse(sse.byteInputStream()) { events += it }

        assertEquals("Voy a leer", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
        val done = assertIs<StreamEvent.Done>(events.last())
        assertEquals(StopReason.TOOL_USE, done.completion.stopReason)
        assertEquals(19, done.completion.usage.total)
        val calls = done.completion.message.toolUses
        assertEquals(listOf("call_1", "call_2"), calls.map { it.id })
        assertEquals("a.txt", calls[0].input["path"]!!.jsonPrimitive.content)
        assertEquals("b.txt", calls[1].input["path"]!!.jsonPrimitive.content)
        assertEquals("Voy a leer", done.completion.message.text)
    }

    @Test
    fun `finish_reason length y content_filter se mapean`() = runTest {
        suspend fun stop(reason: String): StopReason {
            var done: StreamEvent.Done? = null
            wire.readSse("""data: {"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"$reason"}]}""".byteInputStream()) { if (it is StreamEvent.Done) done = it }
            return done!!.completion.stopReason
        }
        assertEquals(StopReason.MAX_TOKENS, stop("length"))
        assertEquals(StopReason.REFUSAL, stop("content_filter"))
        assertEquals(StopReason.END_TURN, stop("stop"))
    }

    @Test
    fun `respuesta no streaming se convierte igual`() {
        val body = Json.parseToJsonElement("""
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c9","type":"function","function":{"name":"bash","arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":3,"completion_tokens":4}}
        """).jsonObject
        val completion = wire.fromWireNonStreaming(body)
        assertEquals(StopReason.TOOL_USE, completion.stopReason)
        assertEquals("ls", completion.message.toolUses.single().input["command"]!!.jsonPrimitive.content)
        assertEquals(7, completion.usage.total)
    }

    @Test
    fun `readSse guarda el razonamiento venga como reasoning o como reasoning_content`() = runTest {
        suspend fun opaque(sse: String): JsonObject {
            var done: StreamEvent.Done? = null
            wire.readSse(sse.trimIndent().byteInputStream()) { if (it is StreamEvent.Done) done = it }
            val block = done!!.completion.message.content.filterIsInstance<Block.Opaque>().single()
            assertEquals("openai", block.providerId)
            return block.payload.jsonObject
        }

        // OpenRouter: `reasoning` en cada delta, `reasoning_details` que no usamos y un `null` al cerrar.
        val openrouter = opaque("""
            data: {"choices":[{"index":0,"delta":{"content":"","reasoning":"Hay que ","reasoning_details":[{"type":"reasoning.text","text":"Hay que ","index":0}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"","reasoning":"mirar el csv"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"vale","reasoning":null},"finish_reason":"stop"}]}

            data: [DONE]
        """)
        assertEquals("Hay que mirar el csv", openrouter["reasoning"]!!.jsonPrimitive.content)
        assertTrue("reasoning_content" !in openrouter)

        // DeepSeek y vLLM: el nombre de siempre.
        val deepseek = opaque("""
            data: {"choices":[{"index":0,"delta":{"reasoning_content":"pensando"},"finish_reason":"stop"}]}
        """)
        assertEquals("pensando", deepseek["reasoning_content"]!!.jsonPrimitive.content)
        assertTrue("reasoning" !in deepseek)
    }

    @Test
    fun `el razonamiento vuelve al proveedor con el nombre con el que llego`() {
        val opaque = Block.Opaque("openai", buildJsonObject { put("reasoning", "pensando") })
        val request = Request(
            model = "m", system = "sys", maxTokens = 100, tools = emptyList(),
            messages = listOf(Message(Role.ASSISTANT, listOf(Block.Text("vale"), opaque))),
        )
        val assistant = wire.toWire(request)["messages"]!!.jsonArray[1].jsonObject
        assertEquals("pensando", assistant["reasoning"]!!.jsonPrimitive.content)
    }

    @Test
    fun `respuesta no streaming con reasoning lo guarda como opaco`() {
        val body = Json.parseToJsonElement("""
            {"choices":[{"message":{"role":"assistant","content":"hecho","reasoning":"pensando"},"finish_reason":"stop"}]}
        """).jsonObject
        val block = wire.fromWireNonStreaming(body).message.content.filterIsInstance<Block.Opaque>().single()
        assertEquals("pensando", block.payload.jsonObject["reasoning"]!!.jsonPrimitive.content)
    }
}
