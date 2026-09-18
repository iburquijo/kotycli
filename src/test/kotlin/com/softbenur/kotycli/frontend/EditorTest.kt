package com.softbenur.kotycli.frontend

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTest {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun `VISUAL gana a EDITOR y se parte respetando las comillas`() {
        val env = mapOf("VISUAL" to "code -w", "EDITOR" to "vi")
        assertEquals(listOf("code", "-w"), Editor.command(env::get, windows = false))
        assertEquals(listOf("vi"), Editor.command({ if (it == "EDITOR") "vi" else null }, windows = false))
        assertEquals(listOf("/opt/mi editor/bin", "-n"), Editor.split("'/opt/mi editor/bin' -n"))
    }

    @Test
    fun `sin VISUAL ni EDITOR queda el del sistema`() {
        assertEquals(listOf("vi"), Editor.command({ null }, windows = false))
        assertEquals(listOf("notepad"), Editor.command({ "  " }, windows = true))
    }

    @Test
    fun `un editor que no existe se cuenta como fallo, no como mensaje vacío`() {
        val result = Editor.open("hola", command = listOf("kotycli-editor-que-no-existe"))
        assertContains(assertIs<EditResult.Failed>(result).message, "no se pudo abrir")
    }

    @Test
    fun `lo que el editor deja en el fichero es el mensaje`() {
        if (windows) return // hace falta un shell para simular al editor; en Windows basta con el test de fallo
        assertEquals("mensaje escrito", (Editor.open("", command = shell("printf 'mensaje escrito' > \"\$0\"")) as EditResult.Text).text)
        assertIs<EditResult.Empty>(Editor.open("borrado", command = shell("printf '' > \"\$0\"")))
        // El editor ve lo que ya había escrito el usuario en `/edit texto`.
        assertEquals("previo", (Editor.open("previo", command = shell("true")) as EditResult.Text).text)
    }

    /** `sh -c script fichero`: dentro del script el fichero es `$0`. */
    private fun shell(script: String) = listOf("sh", "-c", script)
}

class ClipboardTest {
    @Test
    fun `sin ningún comando disponible copy devuelve null`() {
        assertNull(Clipboard.copy("hola", candidates = listOf(listOf("kotycli-portapapeles-que-no-existe"))))
    }

    @Test
    fun `osc52 lleva el texto en base64 y recorta lo que no cabe`() {
        val sequence = Clipboard.osc52("hola")
        assertTrue(sequence.startsWith("]52;c;") && sequence.endsWith(""))
        val payload = sequence.removePrefix("]52;c;").removeSuffix("")
        assertEquals("hola", String(Base64.getDecoder().decode(payload)))
        assertTrue(Clipboard.osc52("x".repeat(500_000)).length < 200_000)
    }
}
