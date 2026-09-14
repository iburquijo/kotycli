package com.softbenur.kotycli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue

/** Reglas de dependencias entre paquetes (doc 07 y ADR 0002), verificadas sobre las fuentes. */
class ArchitectureTest {
    private val root: Path = Path.of("src/main/kotlin/com/softbenur/kotycli")
    private val sources = root.walk().filter { it.name.endsWith(".kt") }.toList()

    @Test
    fun `el core no imprime`() {
        val offenders = sources.filter { p ->
            val rel = p.relativeTo(root).toString()
            !rel.startsWith("frontend") && rel != "Main.kt" && Regex("""\bprintln?\(""").containsMatchIn(p.readText())
        }
        assertTrue(offenders.isEmpty(), "println fuera de frontend/: $offenders")
    }

    @Test
    fun `los tipos de wire de un proveedor no salen de su paquete`() {
        val offenders = sources.filter { p ->
            val rel = p.relativeTo(root).toString()
            !rel.startsWith("providers") && p.readText().contains("com.softbenur.kotycli.providers.openai")
        }
        assertTrue(offenders.isEmpty(), "imports de providers/openai fuera de providers/: $offenders")
    }

    @Test
    fun `nadie depende de frontend y el core no depende de tools concretas`() {
        val offenders = sources.filter { p ->
            val rel = p.relativeTo(root).toString()
            val text = p.readText()
            (!rel.startsWith("frontend") && rel != "Main.kt" && text.contains("import com.softbenur.kotycli.frontend")) ||
                (rel.startsWith("core") && Regex("""import dev\.kotycli\.tools\.(BashTool|ReadTool|EditTool|CreateTool)""").containsMatchIn(text)) ||
                (rel.startsWith("core") && text.contains("import com.softbenur.kotycli.interceptors"))
        }
        assertTrue(offenders.isEmpty(), "dependencias prohibidas: $offenders")
    }
}
