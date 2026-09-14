package com.softbenur.kotycli.agents

/**
 * Un rol de subagente: qué tools puede usar, con qué system prompt y con qué límites.
 * Un subagente es un mecanismo de contexto, no una organización (doc 04): por eso hay dos roles.
 */
data class AgentType(
    val name: String,
    /** Cuándo usarlo. Esto lo ve el modelo padre en la descripción de la tool `task`. */
    val description: String,
    val systemPrompt: String,
    val tools: Set<String>,
    /** null => el modelo del padre (o `subagentModel` de la config). */
    val model: String? = null,
    val maxIterations: Int = 30,
    /** Reglas de permisos solo para este rol, en formato `allow:bash(rg *)`. */
    val permissionRules: List<String> = emptyList(),
) {
    companion object {
        /** Comandos de solo lectura que el explorador puede correr sin preguntar. */
        private val READ_ONLY_BASH = listOf("rg", "fd", "ls", "cat", "head", "tail", "wc", "find", "grep", "git log", "git diff", "git show", "git status")

        val EXPLORER = AgentType(
            name = "explorer",
            description = "Localiza código y responde preguntas sobre el repositorio (dónde está X, cómo funciona Y) sin modificar nada. " +
                "Úsalo cuando la búsqueda vaya a generar mucho ruido: el subagente quema el contexto y te devuelve solo la conclusión.",
            systemPrompt = """
                Eres un subagente explorador. Tu trabajo es buscar en el repositorio y responder la pregunta que te han hecho.

                - No modificas nada: solo lees y buscas.
                - Empieza acotando con `rg`/`grep` y solo entonces lee los ficheros que importan.
                - Termina con una respuesta autocontenida: qué has encontrado, en qué fichero y línea, y lo que haga falta
                  para actuar sin repetir tu búsqueda. Quien te lee no ve nada de lo que has hecho, solo ese último mensaje.
                - Si no encuentras algo, dilo claramente en vez de inventarlo.
            """.trimIndent(),
            tools = setOf("read", "fetch", "bash"),
            permissionRules = READ_ONLY_BASH.map { "allow:bash($it *)" } + READ_ONLY_BASH.map { "allow:bash($it)" },
        )

        val IMPLEMENTOR = AgentType(
            name = "implementor",
            description = "Ejecuta un cambio acotado y bien especificado (editar unos ficheros, aplicar un patrón repetitivo) y devuelve un resumen. " +
                "Solo si sabes exactamente qué hay que hacer: el subagente no ve tu conversación.",
            systemPrompt = """
                Eres un subagente implementador. Recibes un encargo cerrado y lo ejecutas.

                - Lee antes de escribir y haz solo lo que te piden; nada de mejoras por tu cuenta.
                - Si el proyecto tiene build o tests, ejecútalos después de cambiar algo.
                - Si el encargo es ambiguo o resulta imposible, no improvises: termina explicando el problema.
                - Termina con un resumen corto: qué ficheros has tocado y qué has hecho en cada uno.
            """.trimIndent(),
            tools = setOf("read", "edit", "create", "bash", "fetch"),
        )

        val BUILTIN = listOf(EXPLORER, IMPLEMENTOR)
    }
}
