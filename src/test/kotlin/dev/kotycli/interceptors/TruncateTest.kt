package dev.kotycli.interceptors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruncateTest {
    @Test
    fun `conserva cabeza y cola y avisa`() {
        val text = (1..10_000).joinToString("\n") { "línea $it" }
        val out = Truncate.truncate(text, 2000)
        assertTrue(out.startsWith("línea 1\n"))
        assertTrue(out.endsWith("línea 10000"))
        assertTrue(out.contains("caracteres omitidos"))
        assertTrue(out.length < 2400)
        assertEquals("corto", Truncate.truncate("corto", 2000))
    }
}
