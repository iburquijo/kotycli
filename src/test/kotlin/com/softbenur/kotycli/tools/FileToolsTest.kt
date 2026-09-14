package com.softbenur.kotycli.tools

import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.interceptors.PathGuard
import com.softbenur.kotycli.json
import com.softbenur.kotycli.testContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileToolsTest {
    private val dir: Path = Files.createTempDirectory("kotycli-files")
    private val ctx = testContext(FakeProvider(mutableListOf()), tools = listOf(ReadTool(), EditTool(), CreateTool()), workDir = dir, interceptors = listOf(PathGuard()))
    private val read = ReadTool()
    private val edit = EditTool()
    private val create = CreateTool()

    private suspend fun run(tool: Tool, input: JsonObject): Block.ToolResult = tool.execute(input, ToolContext(ctx, "id"))

    @Test
    fun `read numera lineas y registra el fichero`() = runTest {
        Files.writeString(dir.resolve("a.txt"), "uno\ndos\ntres\n")
        val r = run(read, json("path" to "a.txt"))
        assertFalse(r.isError)
        assertEquals("     1\tuno\n     2\tdos\n     3\ttres", r.content)
        assertTrue(ctx.env.fileTracker.wasRead(dir.resolve("a.txt")))
        val page = run(read, kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive("a.txt")); put("offset", kotlinx.serialization.json.JsonPrimitive(2)); put("limit", kotlinx.serialization.json.JsonPrimitive(1)) })
        assertTrue(page.content.startsWith("     2\tdos") && page.content.contains("offset=3"))
    }

    @Test
    fun `read rechaza binarios y directorios`() = runTest {
        Files.write(dir.resolve("bin.dat"), byteArrayOf(1, 0, 2, 3))
        assertTrue(run(read, json("path" to "bin.dat")).isError)
        assertTrue(run(read, json("path" to ".")).isError)
        assertTrue(run(read, json("path" to "nope.txt")).isError)
    }

    @Test
    fun `edit exige lectura previa, match unico y mtime sin cambios`() = runTest {
        val f = dir.resolve("b.txt")
        Files.writeString(f, "foo bar foo\n")
        assertTrue(run(edit, json("path" to "b.txt", "old_string" to "bar", "new_string" to "baz")).isError)

        run(read, json("path" to "b.txt"))
        val ambiguous = run(edit, json("path" to "b.txt", "old_string" to "foo", "new_string" to "x"))
        assertTrue(ambiguous.isError && ambiguous.content.contains("2 veces"))

        val ok = run(edit, json("path" to "b.txt", "old_string" to "bar", "new_string" to "baz"))
        assertFalse(ok.isError, ok.content)
        assertEquals("foo baz foo\n", Files.readString(f))

        // Cambio externo: mtime distinto => hay que releer.
        Files.writeString(f, "otra cosa\n")
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000))
        val stale = run(edit, json("path" to "b.txt", "old_string" to "otra", "new_string" to "x"))
        assertTrue(stale.isError && stale.content.contains("cambió"))
    }

    @Test
    fun `edit normaliza CRLF para comparar y lo preserva al escribir`() = runTest {
        val f = dir.resolve("crlf.txt")
        Files.writeString(f, "a\r\nb\r\nc\r\n")
        run(read, json("path" to "crlf.txt"))
        val r = run(edit, json("path" to "crlf.txt", "old_string" to "a\nb", "new_string" to "A\nB"))
        assertFalse(r.isError, r.content)
        assertEquals("A\r\nB\r\nc\r\n", Files.readString(f))
    }

    @Test
    fun `replace_all sustituye todas`() = runTest {
        val f = dir.resolve("all.txt")
        Files.writeString(f, "x x x")
        run(read, json("path" to "all.txt"))
        val r = run(edit, kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("all.txt")); put("old_string", kotlinx.serialization.json.JsonPrimitive("x"))
            put("new_string", kotlinx.serialization.json.JsonPrimitive("y")); put("replace_all", kotlinx.serialization.json.JsonPrimitive(true))
        })
        assertFalse(r.isError)
        assertEquals("y y y", Files.readString(f))
    }

    @Test
    fun `create crea directorios y falla si existe`() = runTest {
        val r = run(create, json("path" to "sub/dir/new.txt", "content" to "hola\n"))
        assertFalse(r.isError, r.content)
        assertEquals("hola\n", Files.readString(dir.resolve("sub/dir/new.txt")))
        val again = run(create, json("path" to "sub/dir/new.txt", "content" to "otra"))
        assertTrue(again.isError && again.content.contains("ya existe"))
        assertEquals("hola\n", Files.readString(dir.resolve("sub/dir/new.txt")))
    }

    @Test
    fun `PathGuard deniega rutas fuera del working dir`() = runTest {
        val guard = PathGuard()
        val outside = guard.before(ctx, read, Block.ToolUse("1", "read", json("path" to "../../etc/passwd")))
        assertTrue(outside != null && outside.isError)
        val absolute = guard.before(ctx, read, Block.ToolUse("2", "read", json("path" to "/etc/passwd")))
        assertTrue(absolute != null && absolute.isError)
        assertEquals(null, guard.before(ctx, read, Block.ToolUse("3", "read", json("path" to "sub/../a.txt"))))
        assertEquals(null, guard.before(ctx, read, Block.ToolUse("4", "read", json("path" to dir.resolve("a.txt").toString()))))
    }
}
