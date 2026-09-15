package com.softbenur.kotycli.frontend

import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Copiar al portapapeles sin dependencias ni AWT (que en un terminal sin display revienta o se cuelga).
 * Primero el comando del sistema; si no hay ninguno, queda `osc52`, que copia en el terminal del usuario
 * aunque kotycli esté corriendo al otro lado de un ssh.
 */
object Clipboard {
    /** Devuelve el comando que ha copiado, o null si ninguno estaba disponible. */
    fun copy(text: String, candidates: List<List<String>> = candidates()): String? {
        for (cmd in candidates) {
            if (run(cmd, text)) return cmd.first()
        }
        return null
    }

    /**
     * Secuencia OSC 52: el contenido viaja en base64 dentro de un escape y lo copia el emulador de terminal.
     * Muchos terminales la limitan a unos 100 KB, así que el texto se recorta antes de codificar.
     */
    fun osc52(text: String): String {
        val payload = Base64.getEncoder().encodeToString(text.take(MAX_OSC52).toByteArray(Charsets.UTF_8))
        return "]52;c;$payload"
    }

    private fun candidates(os: String = System.getProperty("os.name").orEmpty().lowercase()): List<List<String>> = when {
        os.contains("win") -> listOf(listOf("clip"))
        os.contains("mac") || os.contains("darwin") -> listOf(listOf("pbcopy"))
        else -> listOf(
            listOf("wl-copy"),
            listOf("xclip", "-selection", "clipboard"),
            listOf("xsel", "--clipboard", "--input"),
        )
    }

    private fun run(cmd: List<String>, text: String): Boolean = try {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        // `clip` y `xclip` terminan solos al cerrar stdin; el timeout es por si alguno se queda esperando.
        if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() == 0 else { process.destroy(); false }
    } catch (e: Exception) {
        false
    }

    private const val MAX_OSC52 = 100_000
}
