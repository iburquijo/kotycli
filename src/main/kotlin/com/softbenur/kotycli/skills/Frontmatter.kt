package com.softbenur.kotycli.skills

/**
 * Frontmatter YAML plano (`clave: valor`) al principio de un markdown. Lo comparten los skills
 * y los roles de subagente, que usan el mismo formato de fichero (docs 04 y 05).
 */
data class Frontmatter(val fields: Map<String, String>, val body: String) {
    operator fun get(key: String): String? = fields[key]?.takeIf { it.isNotEmpty() }

    /** Lista separada por comas, con o sin corchetes: `tools: read, bash` o `tools: [read, bash]`. */
    fun list(key: String): List<String>? = get(key)?.trim('[', ']')?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    companion object {
        private val DELIMITER = Regex("""^---\s*$""")

        fun parse(text: String): Frontmatter {
            val lines = text.replace("\r\n", "\n").lines()
            if (lines.firstOrNull()?.let { DELIMITER.matches(it) } != true) return Frontmatter(emptyMap(), text.trim())
            val end = lines.drop(1).indexOfFirst { DELIMITER.matches(it) }
            if (end < 0) return Frontmatter(emptyMap(), text.trim())

            val fields = mutableMapOf<String, String>()
            for (line in lines.subList(1, end + 1)) {
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val colon = line.indexOf(':')
                if (colon <= 0 || line.first().isWhitespace()) continue // sin anidamiento: se ignora lo que no sea `clave: valor`
                fields[line.substring(0, colon).trim()] = line.substring(colon + 1).trim().trim('"', '\'')
            }
            return Frontmatter(fields, lines.drop(end + 2).joinToString("\n").trim())
        }
    }
}
