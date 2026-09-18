package com.softbenur.kotycli.providers

import com.softbenur.kotycli.core.Usage
import com.softbenur.kotycli.providers.openai.OpenAiWire
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La contabilidad de la caché de prefijo. El invariante que lo gobierna todo: `promptTokens` tiene que
 * volver a dar lo que el gateway llamó tamaño del prompt, con los dos nombres que usan los wires.
 */
class UsageTest {
    private val wire = OpenAiWire("openai")

    @Test
    fun `sin caché la cuenta es la de siempre`() {
        val usage = wire.parseUsage(buildJsonObject { put("prompt_tokens", 120); put("completion_tokens", 20) })
        assertEquals(Usage(inputTokens = 120, outputTokens = 20), usage)
        assertEquals(120, usage.promptTokens)
        assertEquals(140, usage.total)
    }

    /** Convención OpenAI: `cached_tokens` es un subconjunto de `prompt_tokens`, así que se resta. */
    @Test
    fun `los cacheados del wire de OpenAI salen de dentro de prompt_tokens`() {
        val usage = wire.parseUsage(buildJsonObject {
            put("prompt_tokens", 10_000)
            put("completion_tokens", 50)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", 9_000) }
        })
        assertEquals(1_000, usage.inputTokens, "solo lo que se procesó al precio completo")
        assertEquals(9_000, usage.cacheReadTokens)
        assertEquals(10_000, usage.promptTokens, "el prompt entero sigue siendo el que dijo el gateway")
    }

    /** Convención Anthropic, que algunos gateways reenvían: distingue lectura de escritura. */
    @Test
    fun `los nombres del wire de Anthropic también se entienden`() {
        val usage = wire.parseUsage(buildJsonObject {
            put("prompt_tokens", 10_000)
            put("completion_tokens", 50)
            put("cache_read_input_tokens", 7_000)
            put("cache_creation_input_tokens", 2_000)
        })
        assertEquals(1_000, usage.inputTokens)
        assertEquals(7_000, usage.cacheReadTokens)
        assertEquals(2_000, usage.cacheWriteTokens)
        assertEquals(10_000, usage.promptTokens)
    }

    /** Si un gateway contase distinto de lo que esperamos, mejor un 0 que un negativo que envenene el presupuesto. */
    @Test
    fun `una cuenta incoherente del gateway no produce tokens negativos`() {
        val usage = wire.parseUsage(buildJsonObject {
            put("prompt_tokens", 100)
            put("cache_read_input_tokens", 9_000)
        })
        assertEquals(0, usage.inputTokens)
    }

    @Test
    fun `sumar usages suma también los de caché`() {
        val a = Usage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 100, cacheWriteTokens = 7)
        val b = Usage(inputTokens = 1, outputTokens = 2, cacheReadTokens = 3, cacheWriteTokens = 4)
        assertEquals(Usage(11, 7, 103, 11), a + b)
        assertEquals(132, (a + b).total)
    }
}
