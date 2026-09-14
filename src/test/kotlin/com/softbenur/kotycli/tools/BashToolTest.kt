package com.softbenur.kotycli.tools

import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.json
import com.softbenur.kotycli.testContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BashToolTest {
    private val dir = Files.createTempDirectory("kotycli-bash").toRealPath()
    private val shell = Shell.detect()
    private val bash = BashTool(shell, dir)
    private val ctx = testContext(FakeProvider(mutableListOf()), tools = listOf(bash), workDir = dir)

    @Test
    fun `mezcla stdout y stderr y anade el exit code`() = runTest {
        val r = bash.execute(json("command" to "echo out; echo err 1>&2; exit 3"), ToolContext(ctx, "1"))
        assertTrue(r.isError)
        assertTrue(r.content.contains("out") && r.content.contains("err"), r.content)
        assertTrue(r.content.endsWith("[exit code: 3]"), r.content)
    }

    @Test
    fun `cd persiste entre llamadas`() = runTest {
        Files.createDirectories(dir.resolve("sub"))
        bash.execute(json("command" to "cd sub"), ToolContext(ctx, "1"))
        assertEquals(dir.resolve("sub"), bash.cwd)
        val r = bash.execute(json("command" to "pwd"), ToolContext(ctx, "2"))
        assertFalse(r.isError)
        assertTrue(r.content.lineSequence().first().endsWith("sub"), r.content)
    }

    @Test
    fun `cancelar mata el proceso y no deja el script temporal`() = runTest {
        val started = System.nanoTime()
        val result = withTimeoutOrNull(500) {
            bash.execute(json("command" to "sleep 30; echo nunca"), ToolContext(ctx, "1"))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue(elapsedMs < 10_000, "la cancelación esperó al comando entero: ${elapsedMs}ms")
        val leftovers = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { s -> s.filter { it.fileName.toString().startsWith("kotycli-") && it.fileName.toString().endsWith(shell.scriptExtension) }.toList() }
        assertTrue(leftovers.isEmpty(), "scripts temporales sin borrar: $leftovers")
    }
}
