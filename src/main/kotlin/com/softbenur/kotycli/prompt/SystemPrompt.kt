package com.softbenur.kotycli.prompt

import com.softbenur.kotycli.config.Dirs
import com.softbenur.kotycli.tools.Shell
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Ensambla el system prompt: base + entorno + `AGENTS.md` (global y del proyecto). Los skills llegan en v2. */
object SystemPrompt {
    fun build(dirs: Dirs, shell: Shell, workDir: Path, today: LocalDate = LocalDate.now()): String {
        val parts = mutableListOf(BASE.trimIndent())
        parts += """
            # Entorno
            - Sistema operativo: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}
            - Shell de la tool `bash`: ${shell.displayName}
            - Directorio de trabajo: $workDir
            - Fecha: $today
        """.trimIndent()
        agentsFiles(dirs, workDir).forEach { (path, text) ->
            parts += "# Instrucciones de $path\n\n$text"
        }
        return parts.joinToString("\n\n")
    }

    /** `~/.agents/AGENTS.md` primero y después el `AGENTS.md` de la raíz del proyecto. */
    fun agentsFiles(dirs: Dirs, workDir: Path): List<Pair<Path, String>> {
        val candidates = listOf(dirs.userAgentsDir.resolve("AGENTS.md"), workDir.resolve("AGENTS.md"))
        return candidates.filter { Files.isRegularFile(it) }.map { it to Files.readString(it).trim() }.filter { it.second.isNotEmpty() }
    }

    private const val BASE = """
        Eres kotycli, un agente de código que trabaja en un terminal sobre el repositorio del usuario.

        Cómo trabajas:
        - Antes de cambiar algo, mira el código: usa `read` para ficheros y `bash` con `rg`, `grep -rn`, `ls` o `git` para orientarte.
        - Haz cambios pequeños y verificables. Después de editar, ejecuta la build o los tests del proyecto si existen.
        - Explica brevemente qué vas a hacer, hazlo con las tools, y termina con un resumen corto de lo hecho y lo que queda.
        - Si una tool devuelve un error, léelo y corrige; no repitas la misma llamada sin cambios.
        - No inventes rutas ni APIs: si no lo has visto, compruébalo.
        - Responde en el idioma en que te habla el usuario.
    """
}
