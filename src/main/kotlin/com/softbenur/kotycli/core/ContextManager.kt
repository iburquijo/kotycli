package com.softbenur.kotycli.core

import com.softbenur.kotycli.providers.Request
import com.softbenur.kotycli.providers.collectToCompletion
import kotlinx.coroutines.CancellationException

/** Resultado de una compactación, para que el frontend sepa qué contar. */
sealed interface CompactResult {
    data class Done(val before: Int, val after: Int) : CompactResult
    /** No había prefijo que resumir: la conversación cabe entera en las últimas rondas. */
    data object NothingToDo : CompactResult
    data class Failed(val message: String) : CompactResult
}

/**
 * Corre antes de cada llamada al modelo. Niveles 2 y 3 del diseño (doc 02): poda de `ToolResult` viejos
 * cuando el proveedor permite editar historial, y compactación cuando ni con eso cabe.
 */
class ContextManager(
    private val keepRounds: Int = 6,
    private val pruneThreshold: Double = 0.75,
    private val compactThreshold: Double = 0.90,
    /** Turnos de usuario que la compactación conserva literales detrás del resumen. */
    private val keepTurns: Int = 2,
) {
    suspend fun prepare(ctx: AgentContext) {
        val window = ctx.provider.capabilities.contextWindow
        if (window <= 0) return
        if (estimateTokens(ctx) >= window * compactThreshold) {
            compact(ctx)
            return
        }
        if (!ctx.provider.capabilities.allowsHistoryEdits) return
        if (estimateTokens(ctx) < window * pruneThreshold) return
        prune(ctx)
    }

    /** El `usage` de la última respuesta como medida real; sin él (sesión nueva o recién compactada), caracteres/4. */
    fun estimateTokens(ctx: AgentContext): Int {
        val last = ctx.lastUsage ?: return rawEstimate(ctx)
        val sinceLast = ctx.messages.takeLastWhile { it.role == Role.USER }
        return last.inputTokens + last.outputTokens + sinceLast.sumOf { charCount(it) / 4 }
    }

    /** Solo caracteres. Para contar la reducción de una compactación hay que medir los dos lados con la misma vara. */
    private fun rawEstimate(ctx: AgentContext): Int =
        (ctx.config.systemPrompt.length + ctx.messages.sumOf { charCount(it) }) / 4

    /**
     * Nivel 3: se le pide al modelo un resumen de la conversación y el historial pasa a ser
     * `[resumen] + [últimos keepTurns turnos]`. Es historial nuevo, no una edición del viejo (ADR 0006).
     */
    suspend fun compact(ctx: AgentContext, instructions: String? = null): CompactResult {
        val cut = tailStart(ctx.messages)
        if (cut <= 0) return CompactResult.NothingToDo
        val before = rawEstimate(ctx)
        val summary = try {
            summarize(ctx, instructions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CompactResult.Failed(e.message ?: e::class.simpleName ?: "error")
        }
        if (summary.isBlank()) return CompactResult.Failed("el modelo devolvió un resumen vacío")

        val tail = ctx.messages.drop(cut)
        ctx.messages.clear()
        ctx.messages += Message.user("$SUMMARY_HEADER\n\n$summary")
        ctx.messages += tail
        // El `usage` de antes medía el historial viejo: sin borrarlo, la siguiente ronda volvería a compactar.
        ctx.forgetUsage()

        val after = rawEstimate(ctx)
        ctx.emit(AgentEvent.Compacted(ctx.id, before, after))
        return CompactResult.Done(before, after)
    }

    /** Una petición aparte, sin tools y sin el system prompt del agente: solo queremos prosa. */
    private suspend fun summarize(ctx: AgentContext, instructions: String?): String {
        val ask = if (instructions.isNullOrBlank()) SUMMARY_ASK else "$SUMMARY_ASK\n\nAdemás, el usuario pide: $instructions"
        val request = Request(
            model = ctx.config.model,
            system = SUMMARY_SYSTEM,
            messages = ctx.messages + Message.user(ask),
            tools = emptyList(),
            maxTokens = ctx.config.maxOutputTokens,
        )
        val completion = ctx.provider.stream(request).collectToCompletion()
        ctx.recordUsage(completion.usage)
        return completion.message.text.trim()
    }

    /**
     * Dónde empieza la cola que se conserva literal: el `keepTurns`-ésimo mensaje de usuario contando
     * desde el final. Solo cuentan los turnos de verdad, no los mensajes que llevan `ToolResult`:
     * cortar en uno de esos dejaría resultados sin su `ToolUse` y el proveedor rechazaría la petición.
     */
    private fun tailStart(messages: List<Message>): Int {
        val turns = messages.indices.filter { i ->
            messages[i].role == Role.USER && messages[i].content.none { it is Block.ToolResult }
        }
        return turns.takeLast(keepTurns).firstOrNull() ?: messages.size
    }

    private fun charCount(m: Message): Int = m.content.sumOf {
        when (it) {
            is Block.Text -> it.text.length
            is Block.ToolResult -> it.content.length
            is Block.ToolUse -> it.input.toString().length
            is Block.Opaque -> it.payload.toString().length
        }
    }

    private fun prune(ctx: AgentContext) {
        val resultIdx = ctx.messages.withIndex().filter { (_, m) -> m.content.any { it is Block.ToolResult } }.map { it.index }
        val toPrune = resultIdx.dropLast(keepRounds)
        for (i in toPrune) {
            val m = ctx.messages[i]
            if (m.content.all { it is Block.ToolResult && it.content == PRUNED }) continue
            ctx.messages[i] = Message(m.role, m.content.map {
                if (it is Block.ToolResult) it.copy(content = PRUNED) else it
            })
        }
    }

    companion object {
        const val PRUNED = "[resultado descartado para liberar contexto; vuelve a ejecutar la tool si lo necesitas]"

        const val SUMMARY_HEADER = "# Resumen de la conversación anterior"

        private const val SUMMARY_SYSTEM =
            "Resumes conversaciones entre un usuario y un agente de código para que el agente pueda seguir " +
                "trabajando sin el historial completo. Escribes solo el resumen, sin preámbulo ni despedida."

        private val SUMMARY_ASK = """
            Resume la conversación anterior. El resumen sustituirá al historial, así que tiene que bastarse solo.
            Escribe en secciones cortas:

            - Qué pedía el usuario, con sus palabras si importan.
            - Qué se ha hecho ya: ficheros tocados (con su ruta), comandos ejecutados y qué devolvieron.
            - Qué se ha aprendido del repositorio y que costaría volver a averiguar.
            - Qué falta por hacer y cuál es el siguiente paso.

            Conserva rutas, nombres de símbolos, números y mensajes de error literales: son lo que no se puede
            reconstruir. Omite lo que ya no afecte al trabajo que queda.
        """.trimIndent()
    }
}
