package com.softbenur.kotycli.interceptors

import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.ToolInterceptor
import com.softbenur.kotycli.tools.Shell
import com.softbenur.kotycli.tools.Tool
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Canonicaliza las rutas del input contra el working dir y deniega las que se salen.
 * Una tool nunca ve `../../etc`. Compara `Path`s reales, nunca strings; case-insensitive en Windows.
 */
class PathGuard : ToolInterceptor {
    override suspend fun before(ctx: AgentContext, tool: Tool, call: Block.ToolUse): Block.ToolResult? {
        if (tool.pathFields.isEmpty()) return null
        val roots = (listOf(ctx.env.workDir) + ctx.env.allowedPaths).map { canonical(it) }
        for (field in tool.pathFields) {
            val raw = call.input[field]?.jsonPrimitive?.content ?: continue
            val resolved = canonical(ctx.env.resolve(raw))
            if (roots.none { isUnder(resolved, it) }) {
                return Block.ToolResult(call.id, "Denegado: $raw está fuera del working dir (${ctx.env.workDir}). Usa --allow-path para permitirlo", isError = true)
            }
        }
        return null
    }

    companion object {
        /** Ruta real del ancestro existente más cercano + el resto, para resolver symlinks también en ficheros aún no creados. */
        fun canonical(path: Path): Path {
            val abs = path.toAbsolutePath().normalize()
            var existing: Path? = abs
            val tail = mutableListOf<Path>()
            while (existing != null && !Files.exists(existing)) {
                existing.fileName?.let { tail.add(0, it) }
                existing = existing.parent
            }
            val real = existing?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: abs.root
            return tail.fold(real ?: abs) { acc, p -> acc.resolve(p) }
        }

        fun isUnder(path: Path, root: Path): Boolean {
            if (!Shell.isWindows) return path.startsWith(root)
            val p = path.toString().lowercase()
            val r = root.toString().lowercase().trimEnd('\\')
            return p == r || p.startsWith("$r\\")
        }
    }
}
