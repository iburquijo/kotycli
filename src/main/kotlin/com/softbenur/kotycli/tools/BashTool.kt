package com.softbenur.kotycli.tools

import com.softbenur.kotycli.core.Block
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Serializable
data class BashInput(
    @Description("Comando a ejecutar. Se ejecuta tal cual en el shell indicado en la descripción de la tool") val command: String,
    @Description("Descripción corta (5-10 palabras) de qué hace el comando, para mostrar al usuario") val description: String? = null,
)

/**
 * Ejecuta un comando con timeout, stdout y stderr mezclados en orden y el exit code al final.
 * `cwd` persiste entre llamadas; el resto del estado del shell no.
 */
class BashTool(private val shell: Shell, initialCwd: Path) : TypedTool<BashInput>(BashInput.serializer()) {
    @Volatile
    var cwd: Path = initialCwd
        private set

    override val name = "bash"
    override val readOnly = false
    override val timeout: Duration = 2.minutes
    override val description: String = """
        Ejecuta un comando en ${shell.displayName} y devuelve stdout y stderr mezclados, seguidos del exit code.
        El directorio actual persiste entre llamadas (un `cd` afecta a las siguientes); variables y funciones no.
        Timeout de ${timeout.inWholeMinutes} minutos; para procesos largos usa `timeout` o lánzalos en segundo plano.
        Para leer o editar ficheros usa `read`, `edit` y `create`, no `cat`/`sed`. Para buscar, usa `rg` o `grep -rn`.
        Nunca ejecutes comandos interactivos (editores, `git rebase -i`, prompts).
    """.trimIndent()

    override suspend fun run(input: BashInput, ctx: ToolContext): Block.ToolResult {
        if (input.command.isBlank()) return ctx.error("El comando está vacío")
        val startCwd = cwd
        if (!Files.isDirectory(startCwd)) return ctx.error("El directorio actual ya no existe: $startCwd")

        val script = shell.wrap(input.command, startCwd)
        val process = ProcessBuilder(shell.command(script))
            .directory(startCwd.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .start()

        val (output, exitCode) = try {
            coroutineScope {
                val reader = async(Dispatchers.IO) { readCapped(process.inputStream, MAX_CAPTURE) }
                val bytes = reader.await()
                val rc = runInterruptible(Dispatchers.IO) { process.waitFor() }
                bytes to rc
            }
        } catch (e: CancellationException) {
            killTree(process)
            throw e
        }

        val (text, newCwd) = splitMarker(output)
        if (newCwd != null) {
            val candidate = Path.of(newCwd)
            if (Files.isDirectory(candidate)) cwd = candidate.toAbsolutePath().normalize()
        }
        val body = buildString {
            append(text.trimEnd())
            if (isNotEmpty()) append('\n')
            append("[exit code: $exitCode]")
        }
        return if (exitCode == 0) ctx.ok(body) else ctx.error(body)
    }

    private fun splitMarker(output: String): Pair<String, String?> {
        val idx = output.lastIndexOf(Shell.CWD_MARKER)
        if (idx < 0) return output to null
        val marker = output.substring(idx + Shell.CWD_MARKER.length).lineSequence().first().trim()
        return output.substring(0, idx) to marker.ifEmpty { null }
    }

    private fun readCapped(stream: java.io.InputStream, cap: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var truncated = false
        stream.use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                if (buffer.size() < cap) buffer.write(chunk, 0, minOf(n, cap - buffer.size())) else truncated = true
            }
        }
        val text = buffer.toString(Charsets.UTF_8)
        return if (truncated) "$text\n[salida cortada en ${cap / 1024} KB]" else text
    }

    companion object {
        const val MAX_CAPTURE = 4 * 1024 * 1024

        /** `Process.destroy()` en Windows no mata a los hijos: siempre descendientes primero, en orden inverso. */
        fun killTree(process: Process) {
            val handles = process.toHandle().descendants().toList().asReversed()
            handles.forEach { runCatching { it.destroyForcibly() } }
            process.destroyForcibly()
        }

        private fun nullDevice(): java.io.File = java.io.File(if (Shell.isWindows) "NUL" else "/dev/null")
    }
}
