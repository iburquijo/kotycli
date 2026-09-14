package dev.kotycli.core

import java.util.concurrent.atomic.AtomicInteger

/** Presupuesto de tokens de la sesión. Compartido entre el agente raíz y todos sus subagentes. */
class Budget(val maxTokens: Int? = null) {
    private val consumed = AtomicInteger(0)

    val tokensConsumed: Int get() = consumed.get()

    fun consume(usage: Usage) {
        consumed.addAndGet(usage.total)
    }

    fun exceeded(): Boolean = maxTokens != null && consumed.get() >= maxTokens
}
