package com.softbenur.kotycli.skills

import com.softbenur.kotycli.config.Dirs
import java.nio.file.Files
import java.nio.file.Path

/** Una carpeta con un `SKILL.md`: instrucciones para una tarea concreta, no código (ADR 0007). */
data class Skill(val name: String, val description: String, val path: Path) {
    /** El cuerpo se lee del disco en cada uso: editar un `SKILL.md` a mitad de sesión tiene efecto sin reiniciar. */
    fun body(): String = Frontmatter.parse(Files.readString(path)).body
}

class SkillCatalog(val all: List<Skill>) {
    operator fun get(name: String): Skill? = all.firstOrNull { it.name == name }

    fun names(): List<String> = all.map { it.name }

    /** Nivel 1 de la carga progresiva: nombre, descripción y ruta en el system prompt. El cuerpo lo lee el modelo con `read`. */
    fun promptSection(): String? {
        if (all.isEmpty()) return null
        return buildString {
            appendLine("# Skills disponibles")
            appendLine()
            appendLine("Instrucciones para tareas concretas de este equipo o proyecto. Si una encaja con lo que te piden,")
            appendLine("lee su `SKILL.md` con `read` antes de empezar y sigue lo que diga.")
            appendLine()
            all.forEach { appendLine("- ${it.name} — ${it.description} — ${it.path}") }
        }.trim()
    }

    /** `/nombre args` escrito por el usuario: el mensaje que se manda es el cuerpo del skill más los argumentos. */
    fun expand(line: String): String? {
        if (!line.startsWith("/")) return null
        val name = line.drop(1).substringBefore(' ')
        val skill = this[name] ?: return null
        val args = line.drop(1).substringAfter(' ', "").trim()
        return if (args.isEmpty()) skill.body() else "${skill.body()}\n\nArgumentos: $args"
    }
}

/**
 * Escanea `~/.agents/skills/` y los `.agents/skills/` del proyecto (desde la raíz del repo hasta el
 * directorio actual). Gana el más específico si dos skills se llaman igual.
 */
object SkillLoader {
    fun load(dirs: Dirs, workDir: Path = dirs.project): SkillCatalog {
        val byName = LinkedHashMap<String, Skill>()
        for (dir in listOf(dirs.userAgentsDir) + projectDirs(workDir).map { it.resolve(".agents") }) {
            for (skill in scan(dir.resolve("skills"))) byName[skill.name] = skill
        }
        return SkillCatalog(byName.values.sortedBy { it.name })
    }

    /** Del directorio más externo al actual, para que el más cercano pise al de la raíz del repo. */
    private fun projectDirs(workDir: Path): List<Path> {
        val chain = generateSequence(workDir) { it.parent }.toMutableList()
        val repoRoot = chain.indexOfFirst { Files.isDirectory(it.resolve(".git")) }
        return (if (repoRoot >= 0) chain.take(repoRoot + 1) else listOf(workDir)).reversed()
    }

    private fun scan(dir: Path): List<Skill> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.sorted().toList() }.mapNotNull { folder ->
            val file = folder.resolve("SKILL.md")
            if (!Files.isRegularFile(file)) return@mapNotNull null
            val fm = try { Frontmatter.parse(Files.readString(file)) } catch (e: Exception) { return@mapNotNull null }
            val description = fm["description"] ?: return@mapNotNull null
            Skill(fm["name"] ?: folder.fileName.toString(), description, file)
        }
    }
}
