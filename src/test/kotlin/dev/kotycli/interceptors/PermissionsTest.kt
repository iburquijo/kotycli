package dev.kotycli.interceptors

import dev.kotycli.EchoTool
import dev.kotycli.FakeProvider
import dev.kotycli.collectEvents
import dev.kotycli.core.AgentEvent
import dev.kotycli.core.Block
import dev.kotycli.core.PermissionMode
import dev.kotycli.core.PermissionReply
import dev.kotycli.json
import dev.kotycli.testContext
import dev.kotycli.tools.BashTool
import dev.kotycli.tools.EditTool
import dev.kotycli.tools.ReadTool
import dev.kotycli.tools.Shell
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PermissionsTest {
    private val bash = BashTool(Shell.detect(), Path.of("."))
    private val edit = EditTool()
    private val read = ReadTool()

    @Test
    fun `parsea reglas y hace match por glob`() {
        val r = Rule.parse("bash(git status*)", Rule.Kind.ALLOW)
        assertEquals("bash", r.tool)
        assertTrue(r.matches("bash", "git status --short"))
        assertTrue(!r.matches("bash", "git push"))
        assertTrue(Rule.parse("edit", Rule.Kind.DENY).matches("edit", "cualquier/cosa"))
        assertTrue(Rule.parse("create(/etc/*)", Rule.Kind.DENY).matches("create", "/etc/ssh/sshd_config"))
        assertTrue(!Rule.parse("create(/etc/*)", Rule.Kind.DENY).matches("create", "/home/x"))
    }

    @Test
    fun `deny gana a allow, allow gana a ask, y el modo decide el resto`() {
        val policy = RulePolicy(listOf(
            Rule.parse("bash(git *)", Rule.Kind.ALLOW),
            Rule.parse("bash(git push --force*)", Rule.Kind.DENY),
            Rule.parse("bash(git rebase*)", Rule.Kind.ASK),
        ))
        val ctx = testContext(FakeProvider(mutableListOf()), mode = PermissionMode.DEFAULT)
        assertEquals(Decision.Allow, policy.decide(ctx, bash, json("command" to "git status")))
        assertIs<Decision.Deny>(policy.decide(ctx, bash, json("command" to "git push --force origin main")))
        assertEquals(Decision.Allow, policy.decide(ctx, bash, json("command" to "git rebase main"))) // allow gana a ask
        assertEquals(Decision.Ask, policy.decide(ctx, bash, json("command" to "rm -rf build")))
        assertEquals(Decision.Allow, policy.decide(ctx, read, json("path" to "x")))
        assertEquals(Decision.Ask, policy.decide(ctx, edit, json("path" to "x")))

        val acceptEdits = testContext(FakeProvider(mutableListOf()), mode = PermissionMode.ACCEPT_EDITS)
        assertEquals(Decision.Allow, policy.decide(acceptEdits, edit, json("path" to "x")))
        assertEquals(Decision.Ask, policy.decide(acceptEdits, bash, json("command" to "make")))
        val yolo = testContext(FakeProvider(mutableListOf()), mode = PermissionMode.YOLO)
        assertEquals(Decision.Allow, policy.decide(yolo, bash, json("command" to "make")))
    }

    @Test
    fun `sin frontend suscrito un Ask se resuelve como Deny`() = runTest {
        val perms = Permissions(RulePolicy(), askTimeout = 1.seconds)
        val ctx = testContext(FakeProvider(mutableListOf()), mode = PermissionMode.DEFAULT)
        val result = perms.before(ctx, bash, Block.ToolUse("1", "bash", json("command" to "make")))
        assertTrue(result != null && result.isError)
    }

    @Test
    fun `ALLOW_SESSION anade una regla y no vuelve a preguntar`() = runTest {
        val perms = Permissions(RulePolicy(), askTimeout = 5.seconds)
        val ctx = testContext(FakeProvider(mutableListOf()), mode = PermissionMode.DEFAULT)
        val asks = mutableListOf<AgentEvent.PermissionAsk>()
        val job = launch {
            ctx.events.collect { if (it is AgentEvent.PermissionAsk) { asks += it; it.reply.complete(PermissionReply.ALLOW_SESSION) } }
        }
        yield()
        val call = Block.ToolUse("1", "bash", json("command" to "make test"))
        assertNull(perms.before(ctx, bash, call))
        assertNull(perms.before(ctx, bash, call))
        job.cancelAndJoin()
        assertEquals(1, asks.size)
        assertEquals("make test", asks.single().subject)
        assertTrue(perms.policy.rules().any { it.tool == "bash" && it.decision == Rule.Kind.ALLOW })
    }

    @Test
    fun `una denegacion llega al modelo como tool result de error`() = runTest {
        val perms = Permissions(RulePolicy(listOf(Rule.parse("echo", Rule.Kind.DENY))))
        val ctx = testContext(FakeProvider(mutableListOf(FakeProvider.toolCall("c1", "echo", json("text" to "x")), FakeProvider.text("ok"))), tools = listOf(EchoTool()), interceptors = listOf(perms))
        val (events, job) = collectEvents(ctx)
        dev.kotycli.core.runLoop(ctx, "x")
        yield(); job.cancelAndJoin()
        val result = ctx.messages[2].content.single() as Block.ToolResult
        assertTrue(result.isError && result.content.contains("no permitida"))
        assertTrue(events.none { it is AgentEvent.ToolStart })
    }
}
