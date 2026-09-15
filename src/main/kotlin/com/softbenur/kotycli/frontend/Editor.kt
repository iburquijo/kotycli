package com.softbenur.kotycli.frontend

import java.nio.file.Files
import java.nio.file.Path

sealed interface EditResult {
    data class Text(val text: String) : EditResult
    /** El usuario guardó vacío o salió sin escribir: no se manda nada al modelo. */
    data object Empty : EditResult
    data class Failed(val message: String) : EditResult
}

/** `/edit`: escribir el mensaje en el editor de siempre en vez de en una línea del terminal. */
object Editor {
    /**
     * `$VISUAL` gana a `$EDITOR` (es el de pantalla completa; `$EDITOR` puede ser `ed` en un sistema viejo).
     * Sin ninguno de los dos, lo que seguro hay en cada sistema.
     */
    fun command(env: (String) -> String? = System::getenv, windows: Boolean = isWindows()): List<String>? {
        val configured = listOf("VISUAL", "EDITOR").firstNotNullOfOrNull { env(it)?.takeIf { v -> v.isNotBlank() } }
        if (configured != null) return split(configured)
        return if (windows) listOf("notepad") else listOf("vi")
    }

    /** Abre el editor sobre un temporal con `initial` dentro y devuelve lo que haya al guardar. */
    fun open(initial: String, command: List<String>? = command(), tempDir: Path? = null): EditResult {
        val cmd = command ?: return EditResult.Failed("no hay editor: define \$EDITOR")
        val file = try {
            val path = if (tempDir != null) Files.createTempFile(tempDir, "kotycli-", ".md") else Files.createTempFile("kotycli-", ".md")
            Files.writeString(path, initial)
            path
        } catch (e: Exception) {
            return EditResult.Failed("no se pudo crear el fichero temporal: ${e.message}")
        }
        try {
            // inheritIO: el editor se queda con el terminal entero hasta que salga.
            val exit = ProcessBuilder(cmd + file.toString()).inheritIO().start().waitFor()
            if (exit != 0) return EditResult.Failed("el editor terminó con código $exit")
            val text = Files.readString(file).trim()
            return if (text.isEmpty()) EditResult.Empty else EditResult.Text(text)
        } catch (e: Exception) {
            return EditResult.Failed("no se pudo abrir ${cmd.joinToString(" ")}: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(file) }
        }
    }

    /** `EDITOR="code -w"` o `EDITOR="'/opt/mi editor/bin' -n"`: se parte por espacios respetando las comillas. */
    fun split(command: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in command) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c.isWhitespace() -> if (current.isNotEmpty()) { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) args += current.toString()
        return args
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
