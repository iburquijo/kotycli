package com.softbenur.kotycli.core

import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.collectEvents
import com.softbenur.kotycli.json
import com.softbenur.kotycli.testContext
import com.softbenur.kotycli.providers.Capabilities
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextManagerTest {
    /** Tres turnos, y el primero con una ronda de tool para que el corte tenga dónde equivocarse. */
    private fun AgentContext.givenConversation() {
        messages += Message.user("turno 1")
        messages += Message(Role.ASSISTANT, listOf(Block.ToolUse("c1", "echo", json("text" to "hola"))))
        messages += Message.toolResults(listOf(Block.ToolResult("c1", "eco: " + "x".repeat(4_000))))
        messages += Message.assistant("respuesta 1")
        messages += Message.user("turno 2")
        messages += Message.assistant("respuesta 2")
        messages += Message.user("turno 3")
        messages += Message.assistant("respuesta 3")
    }

    @Test
    fun `compact sustituye el prefijo por un resumen y conserva los ultimos turnos`() = runTest {
        val provider = FakeProvider(mutableListOf(FakeProvider.text("lo hecho hasta ahora")))
        val ctx = testContext(provider)
        ctx.givenConversation()
        val (events, job) = collectEvents(ctx)

        val result = ctx.contextManager.compact(ctx)
        yield(); job.cancelAndJoin()

        val done = assertIs<CompactResult.Done>(result)
        assertTrue(done.after < done.before / 2, "compactar tiene que reducir de verdad: ${done.before} -> ${done.after}")
        assertEquals(5, ctx.messages.size)
        assertContains(ctx.messages[0].text, ContextManager.SUMMARY_HEADER)
        assertContains(ctx.messages[0].text, "lo hecho hasta ahora")
        assertEquals(listOf("turno 2", "respuesta 2", "turno 3", "respuesta 3"), ctx.messages.drop(1).map { it.text })
        assertTrue(events.any { it is AgentEvent.Compacted })
    }

    @Test
    fun `la peticion de resumen va sin tools y lleva el historial entero`() = runTest {
        val provider = FakeProvider(mutableListOf(FakeProvider.text("resumen")))
        val ctx = testContext(provider)
        ctx.givenConversation()

        ctx.contextManager.compact(ctx, instructions = "céntrate en los permisos")

        val request = provider.requests.single()
        assertTrue(request.tools.isEmpty(), "el modelo no debe poder llamar a nada mientras resume")
        assertEquals(9, request.messages.size, "el historial entero más la petición de resumen")
        assertContains(request.messages.last().text, "céntrate en los permisos")
    }

    @Test
    fun `el corte nunca deja tool results sin su tool use`() = runTest {
        val provider = FakeProvider(mutableListOf(FakeProvider.text("resumen")))
        val ctx = testContext(provider)
        for (turn in 0..2) {
            ctx.messages += Message.user("turno $turn")
            ctx.messages += Message.assistant("respuesta $turn")
        }
        // Dos rondas de tool seguidas al final: cortar por número de mensajes caería justo en un tool result.
        repeat(2) { i ->
            ctx.messages += Message(Role.ASSISTANT, listOf(Block.ToolUse("c$i", "echo", json())))
            ctx.messages += Message.toolResults(listOf(Block.ToolResult("c$i", "ok")))
        }

        ctx.contextManager.compact(ctx)

        val tail = ctx.messages.drop(1)
        assertEquals("turno 1", tail.first().text)
        for ((i, m) in tail.withIndex()) {
            val results = m.content.filterIsInstance<Block.ToolResult>()
            if (results.isEmpty()) continue
            val uses = tail[i - 1].toolUses.map { it.id }
            assertTrue(results.all { it.toolUseId in uses }, "tool result huérfano en la posición $i")
        }
    }

    @Test
    fun `sin prefijo que resumir no se llama al modelo`() = runTest {
        val provider = FakeProvider(mutableListOf())
        val ctx = testContext(provider)
        ctx.messages += Message.user("turno 1")
        ctx.messages += Message.assistant("respuesta 1")

        assertIs<CompactResult.NothingToDo>(ctx.contextManager.compact(ctx))
        assertTrue(provider.requests.isEmpty())
        assertEquals(2, ctx.messages.size)
    }

    @Test
    fun `si el proveedor falla el historial queda intacto`() = runTest {
        val provider = FakeProvider(mutableListOf(FakeProvider.failing("502 bad gateway")))
        val ctx = testContext(provider)
        ctx.givenConversation()

        val result = ctx.contextManager.compact(ctx)

        assertIs<CompactResult.Failed>(result)
        assertContains(result.message, "502")
        assertEquals(8, ctx.messages.size)
    }

    @Test
    fun `prepare compacta sola al acercarse al limite y no repite en la ronda siguiente`() = runTest {
        val provider = FakeProvider(
            mutableListOf(FakeProvider.text("resumen")),
            capabilities = Capabilities(contextWindow = 10_000),
        )
        val ctx = testContext(provider)
        ctx.givenConversation()
        ctx.recordUsage(Usage(inputTokens = 9_500, outputTokens = 100))

        ctx.contextManager.prepare(ctx)
        assertEquals(5, ctx.messages.size)
        assertNull(ctx.lastUsage, "el usage de antes medía el historial viejo")

        // Sin guion que gastar: si volviera a compactar, el FakeProvider lanzaría.
        ctx.contextManager.prepare(ctx)
        assertEquals(5, ctx.messages.size)
    }

    @Test
    fun `la poda sustituye los tool results viejos cuando el proveedor deja editar historial`() = runTest {
        val provider = FakeProvider(mutableListOf(), capabilities = Capabilities(contextWindow = 10_000))
        val ctx = testContext(provider, contextManager = ContextManager(keepRounds = 2))
        repeat(5) { i ->
            ctx.messages += Message(Role.ASSISTANT, listOf(Block.ToolUse("c$i", "echo", json())))
            ctx.messages += Message.toolResults(listOf(Block.ToolResult("c$i", "salida larga $i")))
        }
        ctx.recordUsage(Usage(inputTokens = 7_600, outputTokens = 0))

        ctx.contextManager.prepare(ctx)

        val results = ctx.messages.flatMap { it.content.filterIsInstance<Block.ToolResult>() }
        assertEquals(List(3) { ContextManager.PRUNED } + listOf("salida larga 3", "salida larga 4"), results.map { it.content })
    }
}
