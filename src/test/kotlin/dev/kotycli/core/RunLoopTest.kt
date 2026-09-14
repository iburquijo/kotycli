package dev.kotycli.core

import dev.kotycli.EchoTool
import dev.kotycli.FakeProvider
import dev.kotycli.collectEvents
import dev.kotycli.json
import dev.kotycli.testContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunLoopTest {
    @Test
    fun `una ronda de tool y respuesta final`() = runTest {
        val provider = FakeProvider(mutableListOf(
            FakeProvider.toolCall("c1", "echo", json("text" to "hola"), text = "voy a llamar"),
            FakeProvider.text("listo"),
        ))
        val ctx = testContext(provider)
        val (events, job) = collectEvents(ctx)

        runLoop(ctx, "haz eco")
        yield(); job.cancelAndJoin()

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), ctx.messages.map { it.role })
        val result = ctx.messages[2].content.single() as Block.ToolResult
        assertEquals("c1", result.toolUseId)
        assertEquals("eco: hola", result.content)
        assertEquals("listo", ctx.finalText())
        // La segunda request lleva el historial completo, incluido el tool result.
        assertEquals(3, provider.requests[1].messages.size)
        assertTrue(events.any { it is AgentEvent.ToolStart } && events.any { it is AgentEvent.ToolEnd })
        assertIs<AgentEvent.TurnEnd>(events.last())
        assertEquals(30, ctx.budget.tokensConsumed)
    }

    @Test
    fun `tool desconocida y tool que falla vuelven como isError sin tumbar el loop`() = runTest {
        val provider = FakeProvider(mutableListOf(
            FakeProvider.toolCall("c1", "nope", json()),
            FakeProvider.toolCall("c2", "boom", json()),
            FakeProvider.text("fin"),
        ))
        val ctx = testContext(provider, tools = listOf(EchoTool(name = "boom", fail = true)))
        runLoop(ctx, "x")
        val r1 = ctx.messages[2].content.single() as Block.ToolResult
        val r2 = ctx.messages[4].content.single() as Block.ToolResult
        assertTrue(r1.isError && r1.content.contains("desconocida"))
        assertTrue(r2.isError && r2.content.contains("boom"))
        assertEquals("fin", ctx.finalText())
    }

    @Test
    fun `presupuesto de iteraciones termina el turn con evento`() = runTest {
        val script = MutableList(20) { FakeProvider.toolCall("c$it", "echo", json("text" to "$it")) }
        val ctx = testContext(FakeProvider(script), maxIterations = 3)
        val (events, job) = collectEvents(ctx)
        runLoop(ctx, "loop")
        yield(); job.cancelAndJoin()
        assertTrue(events.any { it is AgentEvent.BudgetExceeded && it.what.startsWith("iteraciones") })
        assertEquals(3, ctx.messages.count { it.role == Role.ASSISTANT })
    }

    @Test
    fun `fallo del proveedor emite Failed y deja el historial como estaba`() = runTest {
        val ctx = testContext(FakeProvider(mutableListOf(FakeProvider.failing("HTTP 500"))))
        val (events, job) = collectEvents(ctx)
        runLoop(ctx, "x")
        yield(); job.cancelAndJoin()
        assertTrue(events.any { it is AgentEvent.Failed && it.message.contains("500") })
        assertEquals(1, ctx.messages.size)
    }

    @Test
    fun `cancelar a mitad de ronda cierra la ronda con tool results de error`() = runTest {
        val blocking = EchoTool(block = true)
        val ctx = testContext(FakeProvider(mutableListOf(FakeProvider.toolCall("c1", "echo", json()))), tools = listOf(blocking))
        val job = launch { runLoop(ctx, "x") }
        while (blocking.calls.isEmpty()) yield()
        job.cancelAndJoin()
        val last = ctx.messages.last()
        assertEquals(Role.USER, last.role)
        val result = last.content.single() as Block.ToolResult
        assertTrue(result.isError && result.content == "cancelado" && result.toolUseId == "c1")
    }

    @Test
    fun `los resultados respetan el orden de las llamadas aunque las readOnly vayan en paralelo`() = runTest {
        val slow = EchoTool(name = "slow", readOnly = true)
        val mut = EchoTool(name = "mut", readOnly = false)
        val calls = listOf(
            Block.ToolUse("a", "mut", json("text" to "1")),
            Block.ToolUse("b", "slow", json("text" to "2")),
            Block.ToolUse("c", "mut", json("text" to "3")),
        )
        val ctx = testContext(FakeProvider(mutableListOf()), tools = listOf(slow, mut))
        val results = dispatch(ctx, calls)
        assertEquals(listOf("a", "b", "c"), results.map { it.toolUseId })
    }
}
