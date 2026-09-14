package dev.kotycli.interceptors

import dev.kotycli.core.AgentContext
import dev.kotycli.core.Block
import dev.kotycli.core.ToolInterceptor
import dev.kotycli.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Escribe cada tool call y su resultado a `~/.kotycli/logs/tools.jsonl`. El resultado completo vive aquí, no en la TUI. */
class ToolLog(private val file: Path, private val maxResultChars: Int = 20_000) : ToolInterceptor {
    private val started = ConcurrentHashMap<String, Long>()

    override suspend fun before(ctx: AgentContext, tool: Tool, call: Block.ToolUse): Block.ToolResult? {
        started[call.id] = System.nanoTime()
        write(buildJsonObject {
            put("ts", Instant.now().toString())
            put("agent", ctx.id)
            put("event", "call")
            put("id", call.id)
            put("tool", tool.name)
            put("input", call.input)
        })
        return null
    }

    override suspend fun after(ctx: AgentContext, tool: Tool, call: Block.ToolUse, result: Block.ToolResult): Block.ToolResult {
        val t0 = started.remove(call.id)
        write(buildJsonObject {
            put("ts", Instant.now().toString())
            put("agent", ctx.id)
            put("event", "result")
            put("id", call.id)
            put("tool", tool.name)
            put("isError", result.isError)
            put("durationMs", t0?.let { (System.nanoTime() - it) / 1_000_000 } ?: -1)
            put("content", JsonPrimitive(result.content.take(maxResultChars)))
        })
        return result
    }

    private suspend fun write(obj: kotlinx.serialization.json.JsonObject) = withContext(Dispatchers.IO) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj) + "\n", Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
