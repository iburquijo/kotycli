package com.softbenur.kotycli.tools

import com.softbenur.kotycli.core.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ReadInput(
    @Description("Ruta del fichero, relativa al working dir o absoluta dentro de él") val path: String,
    @Description("Línea (1-based) por la que empezar. Por defecto 1") val offset: Int? = null,
    @Description("Máximo de líneas a devolver. Por defecto 2000") val limit: Int? = null,
)

/** Lee un fichero numerando líneas como `cat -n`. Registra path y mtime para que `edit` pueda comprobar staleness. */
class ReadTool : TypedTool<ReadInput>(ReadInput.serializer()) {
    override val name = "read"
    override val readOnly = true
    override val timeout: Duration = 10.seconds
    override val pathFields = listOf("path")
    override val description = """
        Lee un fichero de texto y lo devuelve con las líneas numeradas. Usa `offset` y `limit` para ficheros grandes.
        Hay que leer un fichero con esta tool antes de poder modificarlo con `edit`. Rechaza binarios.
    """.trimIndent()

    override suspend fun run(input: ReadInput, ctx: ToolContext): Block.ToolResult = withContext(Dispatchers.IO) {
        val path = ctx.resolve(input.path)
        if (!Files.exists(path)) return@withContext ctx.error("No existe: ${input.path}")
        if (Files.isDirectory(path)) return@withContext ctx.error("${input.path} es un directorio. Usa `bash ls` para listarlo")
        if (isBinary(path)) return@withContext ctx.error("${input.path} parece un fichero binario; no se puede leer como texto")

        val offset = (input.offset ?: 1).coerceAtLeast(1)
        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        ctx.fileTracker.record(path)

        if (lines.isEmpty()) return@withContext ctx.ok("(fichero vacío)")
        if (offset > lines.size) return@withContext ctx.error("offset $offset fuera de rango: el fichero tiene ${lines.size} líneas")

        val slice = lines.drop(offset - 1).take(limit)
        val out = StringBuilder()
        slice.forEachIndexed { i, line -> out.append(String.format("%6d\t%s%n", offset + i, line)) }
        val end = offset - 1 + slice.size
        if (end < lines.size) out.append("[... ${lines.size - end} líneas más; sigue con offset=${end + 1}]")
        ctx.ok(out.toString().trimEnd())
    }

    private fun isBinary(path: java.nio.file.Path): Boolean {
        Files.newInputStream(path).use { s ->
            val head = s.readNBytes(8192)
            return head.any { it == 0.toByte() }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 2000
        const val MAX_LIMIT = 10_000
    }
}
