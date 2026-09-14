package dev.kotycli.tools

import dev.kotycli.core.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class EditInput(
    @Description("Ruta del fichero, relativa al working dir") val path: String,
    @Description("Texto exacto a sustituir. Tiene que aparecer una sola vez (salvo replace_all)") val old_string: String,
    @Description("Texto nuevo") val new_string: String,
    @Description("Sustituir todas las apariciones en vez de exigir una única") val replace_all: Boolean = false,
)

/**
 * `str_replace` con match único. Exige lectura previa en la sesión y mtime sin cambios.
 * Normaliza CRLF para comparar y preserva el line ending original al escribir.
 */
class EditTool : TypedTool<EditInput>(EditInput.serializer()) {
    override val name = "edit"
    override val readOnly = false
    override val timeout: Duration = 10.seconds
    override val pathFields = listOf("path")
    override val description = """
        Sustituye `old_string` por `new_string` en un fichero. `old_string` tiene que aparecer exactamente una vez;
        si aparece varias, amplía el contexto para que sea único o usa `replace_all`.
        El fichero tiene que haberse leído antes con `read` en esta sesión y no haber cambiado desde entonces.
        Para reescribir un fichero entero, `old_string` puede ser su contenido completo.
    """.trimIndent()

    override suspend fun run(input: EditInput, ctx: ToolContext): Block.ToolResult = withContext(Dispatchers.IO) {
        val path = ctx.resolve(input.path)
        if (!Files.isRegularFile(path)) return@withContext ctx.error("No existe o no es un fichero: ${input.path}. Para crear uno nuevo usa `create`")
        if (!ctx.fileTracker.wasRead(path)) return@withContext ctx.error("Hay que leer ${input.path} con `read` antes de editarlo")
        if (ctx.fileTracker.isStale(path)) return@withContext ctx.error("${input.path} cambió en disco desde que lo leíste. Vuelve a leerlo antes de editar")
        if (input.old_string.isEmpty()) return@withContext ctx.error("old_string no puede estar vacío")
        if (input.old_string == input.new_string) return@withContext ctx.error("old_string y new_string son idénticos")

        val original = Files.readString(path, Charsets.UTF_8)
        val crlf = original.contains("\r\n")
        val content = original.replace("\r\n", "\n")
        val old = input.old_string.replace("\r\n", "\n")
        val new = input.new_string.replace("\r\n", "\n")

        val count = countOccurrences(content, old)
        if (count == 0) return@withContext ctx.error("old_string no aparece en ${input.path}. Comprueba espacios, indentación y saltos de línea")
        if (count > 1 && !input.replace_all) return@withContext ctx.error("old_string aparece $count veces en ${input.path}; tiene que ser único. Añade contexto o usa replace_all")

        val updated = if (input.replace_all) content.replace(old, new) else content.replaceFirst(old, new)
        val toWrite = if (crlf) updated.replace("\n", "\r\n") else updated
        Files.writeString(path, toWrite, Charsets.UTF_8)
        ctx.fileTracker.record(path)

        val replaced = if (input.replace_all) count else 1
        ctx.ok("Editado ${input.path}: $replaced sustitución${if (replaced == 1) "" else "es"}")
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
