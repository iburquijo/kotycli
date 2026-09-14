package com.softbenur.kotycli.tools

import com.softbenur.kotycli.EchoTool
import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.agents.AgentType
import com.softbenur.kotycli.collectEvents
import com.softbenur.kotycli.core.AgentEvent
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.Role
import com.softbenur.kotycli.core.runLoop
import com.softbenur.kotycli.json
import com.softbenur.kotycli.testContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskToolTest {
    private val types = AgentType.BUILTIN.associateBy { it.name }

    private fun taskCall(id: String, type: String, prompt: String = "busca dónde se valida el token") =
        FakeProvider.toolCall(id, "task", json("agent_type" to type, "prompt" to prompt))

    @Test
    fun `el padre solo recibe el mensaje final del hijo`() = runTest {
        val provider = FakeProvider(mutableListOf(
            taskCall("c1", "explorer"),
            FakeProvider.toolCall("c2", "echo", json("text" to "rg token")), // esto lo pide el hijo
            FakeProvider.text("está en Auth.kt:42"),
            FakeProvider.text("gracias, lo miro"),
        ))
        val ctx = testContext(provider, tools = listOf(EchoTool(name = "read"), TaskTool(types, contextPrompt = "# Entorno")))
        val (events, collector) = collectEvents(ctx)

        runLoop(ctx, "¿dónde se valida el token?")
        yield(); collector.cancelAndJoin()

        // Del transcript del hijo (tool call incluida) no queda nada en el padre: solo su última frase.
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), ctx.messages.map { it.role })
        val result = ctx.messages[2].content.single() as Block.ToolResult
        assertEquals("está en Auth.kt:42", result.content)
        assertFalse(result.isError)
        assertEquals(60, ctx.budget.tokensConsumed) // las cuatro llamadas, las del hijo incluidas
        assertEquals(30, ctx.tokensBurned) // pero el padre solo quema las suyas

        val start = events.filterIsInstance<AgentEvent.SubagentStart>().single()
        assertEquals(ctx.id, start.parentId)
        assertEquals("explorer", start.agentType)
        assertEquals(start.agentId, events.filterIsInstance<AgentEvent.SubagentEnd>().single().agentId)
    }

    @Test
    fun `el hijo arranca con contexto virgen, el prompt de su rol y su toolset`() = runTest {
        val provider = FakeProvider(mutableListOf(
            taskCall("c1", "explorer"),
            FakeProvider.text("nada por aquí"),
            FakeProvider.text("ok"),
        ))
        val tools = listOf(EchoTool(name = "read"), EchoTool(name = "edit", readOnly = false), TaskTool(types, contextPrompt = "# Entorno"))
        runLoop(testContext(provider, tools = tools), "busca")

        val child = provider.requests[1]
        assertEquals(1, child.messages.size) // solo el prompt del encargo
        assertContains(child.system, "subagente explorador")
        assertContains(child.system, "# Entorno")
        assertEquals(listOf("read"), child.tools.map { it.name }) // sin edit y sin task: no hay nietos
    }

    @Test
    fun `agent_type desconocido y profundidad maxima vuelven como error sin lanzar`() = runTest {
        val unknown = FakeProvider(mutableListOf(taskCall("c1", "reviewer"), FakeProvider.text("fin")))
        val ctx = testContext(unknown, tools = listOf(TaskTool(types)))
        runLoop(ctx, "x")
        val result = ctx.messages[2].content.single() as Block.ToolResult
        assertTrue(result.isError && result.content.contains("explorer"))

        // A profundidad 2 (nieto) ni se mira el rol: no hay bisnietos.
        val deep = FakeProvider(mutableListOf(taskCall("c2", "explorer"), FakeProvider.text("fin")))
        val deepCtx = testContext(deep, tools = listOf(TaskTool(types)), depth = 2)
        runLoop(deepCtx, "x")
        val tooDeep = deepCtx.messages[2].content.single() as Block.ToolResult
        assertTrue(tooDeep.isError && tooDeep.content.contains("Profundidad"))
    }

    @Test
    fun `un hijo sin respuesta vuelve como error`() = runTest {
        val provider = FakeProvider(mutableListOf(
            taskCall("c1", "explorer"),
            FakeProvider.failing("502 del proveedor"),
            FakeProvider.text("fin"),
        ))
        val ctx = testContext(provider, tools = listOf(TaskTool(types)))
        runLoop(ctx, "busca")

        val result = ctx.messages[2].content.single() as Block.ToolResult
        assertTrue(result.isError && result.content.contains("sin respuesta"))
    }
}
