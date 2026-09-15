package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.PermissionReply
import com.softbenur.kotycli.frontend.acp.ACP_PROTOCOL_VERSION
import com.softbenur.kotycli.frontend.acp.AcpWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El mapeo a ACP es traducción pura: se prueba sin levantar el protocolo, como `OpenAiWireTest`. */
class AcpWireTest {
    private val workDir: Path = Path.of("/repo").toAbsolutePath()

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `initialize negocia a la baja la version del protocolo`() {
        assertEquals(ACP_PROTOCOL_VERSION, AcpWire.initializeResult(99)["protocolVersion"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, AcpWire.initializeResult(1)["protocolVersion"]?.jsonPrimitive?.content?.toInt())
        // No cargamos sesiones de disco: el cliente no debe ofrecer "continuar conversación".
        assertEquals(false, AcpWire.initializeResult(1)["agentCapabilities"]?.jsonObject?.get("loadSession")?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `el prompt junta los bloques que entendemos y descarta el resto`() {
        val params = parse(
            """
            {"prompt":[
              {"type":"text","text":"arregla el test"},
              {"type":"image","data":"...","mimeType":"image/png"},
              {"type":"resource_link","uri":"file:///repo/Main.kt"}
            ]}
            """.trimIndent()
        )
        assertEquals("arregla el test\nfile:///repo/Main.kt", AcpWire.promptText(params))
        assertEquals("", AcpWire.promptText(parse("""{}""")))
    }

    @Test
    fun `cada tool tiene su kind de ACP`() {
        assertEquals("execute", AcpWire.toolKind("bash"))
        assertEquals("read", AcpWire.toolKind("read"))
        assertEquals("edit", AcpWire.toolKind("edit"))
        assertEquals("edit", AcpWire.toolKind("create"))
        assertEquals("fetch", AcpWire.toolKind("fetch"))
        assertEquals("other", AcpWire.toolKind("task"))
    }

    @Test
    fun `un edit lleva diff y la ruta absoluta`() {
        val input = parse("""{"path":"src/Main.kt","old_string":"a","new_string":"b"}""")
        val call = AcpWire.toolCall("call-1", "edit", input, "pending", workDir)

        assertEquals("tool_call", call["sessionUpdate"]?.jsonPrimitive?.content)
        assertEquals("call-1", call["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("pending", call["status"]?.jsonPrimitive?.content)
        assertTrue(call["title"]?.jsonPrimitive?.content!!.startsWith("edit  src/Main.kt"))

        val location = call["locations"]!!.jsonArray.single().jsonObject
        assertEquals(workDir.resolve("src/Main.kt").toString(), location["path"]?.jsonPrimitive?.content)

        val diff = call["content"]!!.jsonArray.single().jsonObject
        assertEquals("diff", diff["type"]?.jsonPrimitive?.content)
        assertEquals("a", diff["oldText"]?.jsonPrimitive?.content)
        assertEquals("b", diff["newText"]?.jsonPrimitive?.content)
    }

    @Test
    fun `un bash no lleva ni locations ni diff`() {
        val call = AcpWire.toolCall("call-2", "bash", parse("""{"command":"./gradlew test"}"""), "in_progress", workDir)
        assertNull(call["locations"])
        assertNull(call["content"])
        assertEquals("execute", call["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `el resultado de una tool cierra la llamada con su estado`() {
        val call = Block.ToolUse("call-3", "bash", parse("""{"command":"false"}"""))
        val failed = AcpWire.toolCallEnd(AgentEvent.ToolEnd("a1", call, Block.ToolResult("call-3", "boom", isError = true), 12))
        assertEquals("tool_call_update", failed["sessionUpdate"]?.jsonPrimitive?.content)
        assertEquals("failed", failed["status"]?.jsonPrimitive?.content)
        assertEquals("boom", failed["content"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]?.jsonPrimitive?.content)

        val ok = AcpWire.toolCallEnd(AgentEvent.ToolEnd("a1", call, Block.ToolResult("call-3", "hecho"), 12))
        assertEquals("completed", ok["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `el permiso ofrece las tres opciones que sabemos resolver`() {
        val ask = AgentEvent.PermissionAsk("a1", "call-4", "bash", parse("""{"command":"rm -rf /"}"""), "rm -rf /", CompletableDeferred())
        val params = AcpWire.requestPermission("s1", ask, workDir)

        assertEquals("s1", params["sessionId"]?.jsonPrimitive?.content)
        assertEquals("call-4", params["toolCall"]!!.jsonObject["toolCallId"]?.jsonPrimitive?.content)
        assertEquals("pending", params["toolCall"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("allow_once", "allow_always", "reject_once"),
            params["options"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `la respuesta del cliente se traduce a PermissionReply`() {
        fun selected(option: String) = parse("""{"outcome":{"outcome":"selected","optionId":"$option"}}""")
        assertEquals(PermissionReply.ALLOW, AcpWire.permissionReply(selected(AcpWire.ALLOW_ONCE)))
        assertEquals(PermissionReply.ALLOW_SESSION, AcpWire.permissionReply(selected(AcpWire.ALLOW_ALWAYS)))
        assertEquals(PermissionReply.DENY, AcpWire.permissionReply(selected(AcpWire.REJECT_ONCE)))
        // Lo que no entendemos, y la cancelación del cliente, se deniegan.
        assertEquals(PermissionReply.DENY, AcpWire.permissionReply(selected("reject-always")))
        assertEquals(PermissionReply.DENY, AcpWire.permissionReply(parse("""{"outcome":{"outcome":"cancelled"}}""")))
        assertEquals(PermissionReply.DENY, AcpWire.permissionReply(JsonObject(emptyMap())))
    }

    @Test
    fun `el presupuesto agotado distingue tokens de iteraciones`() {
        assertEquals("max_tokens", AcpWire.budgetStopReason("tokens (100000 por sesión)"))
        assertEquals("max_turn_requests", AcpWire.budgetStopReason("iteraciones (50 por turn)"))
    }

    @Test
    fun `la actividad de un subagente se resume en una linea`() {
        val start = AgentEvent.SubagentStart("hijo", "a1", "explorer", "busca dónde se valida el IBAN")
        assertTrue(AcpWire.subagentLine(start)!!.startsWith("● task explorer"))
        assertNull(AcpWire.subagentLine(AgentEvent.TextDelta("hijo", "ruido")))
    }
}
