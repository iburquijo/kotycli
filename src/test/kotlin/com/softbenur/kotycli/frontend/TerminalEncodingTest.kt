package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.frontend.tui.terminalBuilder
import org.jline.terminal.TerminalBuilder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Sin `LANG` UTF-8 la JVM deduce `stdout.encoding = ANSI_X3.4-1968` y todo lo que no es ASCII sale como `?`.
 * Pasó de verdad: la TUI contra un modelo real, en un contenedor sin locale, escribía "conversaci?n" y se
 * comía el `·` del banner. `Main.ensureUtf8Stdout()` no cubre este camino porque la TUI no escribe por
 * `System.out` sino por el writer de JLine, que desde JLine 3.25 usa `stdoutEncoding`, un ajuste distinto
 * de `encoding` (ese es el charset interno del terminal, no el del writer).
 */
class TerminalEncodingTest {
    private var previous: String? = null

    @BeforeTest
    fun forzarLocaleAscii() {
        previous = System.getProperty(TerminalBuilder.PROP_STDOUT_ENCODING)
        System.setProperty(TerminalBuilder.PROP_STDOUT_ENCODING, "ASCII")
    }

    @AfterTest
    fun restaurar() {
        previous?.let { System.setProperty(TerminalBuilder.PROP_STDOUT_ENCODING, it) }
            ?: System.clearProperty(TerminalBuilder.PROP_STDOUT_ENCODING)
    }

    @Test
    fun `la TUI pide UTF-8 para el writer aunque el entorno diga ASCII`() {
        assertEquals(Charsets.UTF_8, terminalBuilder().computeStdoutEncoding())
    }

    /** Sin el ajuste manda el entorno: si esto dejara de cumplirse, el test de arriba ya no probaría nada. */
    @Test
    fun `un builder sin stdoutEncoding se traga lo que diga el entorno`() {
        assertNotEquals(Charsets.UTF_8, TerminalBuilder.builder().encoding(Charsets.UTF_8).computeStdoutEncoding())
    }
}
