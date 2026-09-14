package com.softbenur.kotycli.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * El shell que hay debajo de `bash`, detectado una vez al arrancar y declarado tal cual en la
 * description de la tool y en el system prompt. El modelo no tiene que adivinar.
 */
class Shell(val kind: Kind, val executable: String) {
    enum class Kind { BASH, PWSH, POWERSHELL, SH }

    val displayName: String
        get() = when (kind) {
            Kind.BASH -> if (isWindows) "Git Bash ($executable)" else "bash ($executable)"
            Kind.PWSH -> "PowerShell 7 ($executable)"
            Kind.POWERSHELL -> "Windows PowerShell ($executable)"
            Kind.SH -> "sh ($executable)"
        }

    val isPosix: Boolean get() = kind == Kind.BASH || kind == Kind.SH

    /** Extensión del fichero temporal con el script: PowerShell exige `.ps1` para `-File`. */
    val scriptExtension: String get() = if (isPosix) ".sh" else ".ps1"

    /**
     * El script se ejecuta desde un fichero, nunca como argumento de `-c`/`-Command`: Java en Windows
     * envuelve el argumento en comillas dobles sin escapar las que contiene, y `bash.exe` cierra el
     * argumento en la primera comilla del script. Con un fichero, el único argumento es su ruta.
     */
    fun command(scriptFile: Path): List<String> = when (kind) {
        Kind.BASH, Kind.SH -> listOf(executable, shellPath(scriptFile))
        Kind.PWSH, Kind.POWERSHELL -> listOf(executable, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString())
    }

    /** Bytes del script: UTF-8, y con BOM en PowerShell porque sin él Windows PowerShell asume ANSI. */
    fun scriptBytes(script: String): ByteArray {
        val body = script.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        return if (isPosix) body else byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
    }

    /** Git Bash entiende `C:/Users/...`; las barras invertidas dentro de comillas simples no se convierten. */
    private fun shellPath(p: Path): String = if (isWindows && isPosix) p.toString().replace('\\', '/') else p.toString()

    /**
     * Envuelve el comando del modelo para que arranque en `cwd` y al final imprima el directorio
     * en el que quedó, de modo que `cd` persista entre llamadas. En Git Bash `$PWD` es una ruta POSIX
     * (`/c/Users/...`) que Java no entiende; `pwd -W` devuelve la ruta Windows y en otros bash no existe.
     */
    fun wrap(command: String, cwd: Path): String = if (isPosix) {
        """
        |cd -- ${posixQuote(shellPath(cwd))} || exit 1
        |$command
        |__kc_rc=$?
        |printf '\n%s%s\n' '$CWD_MARKER' "${'$'}(pwd -W 2>/dev/null || pwd)"
        |exit ${'$'}__kc_rc
        """.trimMargin()
    } else {
        """
        |Set-Location -LiteralPath ${psQuote(cwd.toString())}
        |$command
        |${'$'}__kc_rc = if (${'$'}?) { if (${'$'}null -ne ${'$'}LASTEXITCODE) { ${'$'}LASTEXITCODE } else { 0 } } else { 1 }
        |Write-Output ("`n$CWD_MARKER" + (Get-Location).Path)
        |exit ${'$'}__kc_rc
        """.trimMargin()
    }

    companion object {
        const val CWD_MARKER = "__KOTYCLI_CWD__:"
        val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

        fun detect(): Shell {
            if (isWindows) {
                gitBash()?.let { return Shell(Kind.BASH, it) }
                findOnPath("pwsh.exe")?.let { return Shell(Kind.PWSH, it) }
                return Shell(Kind.POWERSHELL, "powershell.exe")
            }
            findOnPath("bash")?.let { return Shell(Kind.BASH, it) }
            return Shell(Kind.SH, "/bin/sh")
        }

        private fun gitBash(): String? {
            val candidates = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })
                .map { Path.of(it, "Git", "bin", "bash.exe") }
            return candidates.firstOrNull { Files.isExecutable(it) }?.toString() ?: findOnPath("bash.exe")?.takeIf { it.contains("Git", ignoreCase = true) }
        }

        private fun findOnPath(name: String): String? {
            val sep = if (isWindows) ";" else ":"
            return System.getenv("PATH")?.split(sep)?.asSequence()
                ?.map { Path.of(it, name) }
                ?.firstOrNull { Files.isExecutable(it) }
                ?.toString()
        }

        private fun posixQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        private fun psQuote(s: String) = "'" + s.replace("'", "''") + "'"
    }
}
