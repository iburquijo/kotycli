package com.softbenur.kotycli.agents

import com.softbenur.kotycli.config.Dirs
import com.softbenur.kotycli.skills.Frontmatter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Roles builtin más los ficheros markdown de `~/.agents/agents/` y de `<proyecto>/.agents/agents/`.
 * El más específico gana si el nombre colisiona: proyecto > usuario > builtin.
 */
object AgentTypeLoader {
    fun load(dirs: Dirs, builtin: List<AgentType> = AgentType.BUILTIN): Map<String, AgentType> {
        val types = LinkedHashMap<String, AgentType>()
        builtin.forEach { types[it.name] = it }
        for (dir in listOf(dirs.userAgentsDir, dirs.projectAgentsDir)) {
            for (file in markdownFiles(dir.resolve("agents"))) {
                parse(file, types)?.let { types[it.name] = it }
            }
        }
        return types
    }

    private fun markdownFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.extension == "md" && Files.isRegularFile(it) }.sorted().toList() }
    }

    /** Un rol necesita cuerpo (su system prompt) y descripción; sin eso el modelo padre no sabría cuándo usarlo. */
    private fun parse(file: Path, known: Map<String, AgentType>): AgentType? {
        val fm = try { Frontmatter.parse(Files.readString(file)) } catch (e: Exception) { return null }
        val name = fm["name"] ?: file.nameWithoutExtension
        val description = fm["description"] ?: return null
        if (fm.body.isEmpty()) return null
        return AgentType(
            name = name,
            description = description,
            systemPrompt = fm.body,
            tools = fm.list("tools")?.toSet() ?: known[name]?.tools ?: AgentType.IMPLEMENTOR.tools,
            model = fm["model"],
            maxIterations = fm["maxIterations"]?.toIntOrNull() ?: 30,
            permissionRules = fm.list("permissions") ?: emptyList(),
        )
    }
}
