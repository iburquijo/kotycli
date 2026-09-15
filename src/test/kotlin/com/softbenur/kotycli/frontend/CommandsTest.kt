package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.skills.Skill
import com.softbenur.kotycli.skills.SkillCatalog
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandsTest {
    private val dir = Files.createTempDirectory("kotycli-skills")

    private fun catalog(vararg names: String): SkillCatalog = SkillCatalog(names.map { name ->
        val file = dir.resolve("$name.md")
        file.writeText("---\nname: $name\ndescription: hace $name\n---\n\nCuerpo de $name.\n")
        Skill(name, "hace $name", file)
    })

    @Test
    fun `los builtin se reconocen con y sin argumentos`() {
        val skills = catalog()
        assertIs<Command.Exit>(Commands.parse("/exit", skills))
        assertIs<Command.Exit>(Commands.parse("/quit", skills))
        assertIs<Command.Help>(Commands.parse("  /help  ", skills))
        assertIs<Command.Config>(Commands.parse("/config", skills))
        assertIs<Command.Copy>(Commands.parse("/copy", skills))
        assertIs<Command.Reload>(Commands.parse("/reload", skills))
        assertEquals(null, (Commands.parse("/compact", skills) as Command.Compact).instructions)
        assertEquals("el bug de permisos", (Commands.parse("/compact el bug de permisos", skills) as Command.Compact).instructions)
        assertEquals("arregla esto", (Commands.parse("/edit arregla esto", skills) as Command.Edit).initial)
    }

    @Test
    fun `un skill se expande a su cuerpo y lo que no existe se avisa`() {
        val skills = catalog("release")
        val skill = assertIs<Command.Skill>(Commands.parse("/release 1.2.0", skills))
        assertEquals("release", skill.name)
        assertContains(skill.expanded, "Cuerpo de release.")
        assertContains(skill.expanded, "Argumentos: 1.2.0")
        assertEquals("nada", assertIs<Command.Unknown>(Commands.parse("/nada", skills)).name)
    }

    /** Un skill llamado `exit` no puede dejar al usuario sin forma de salir. */
    @Test
    fun `los builtin ganan a un skill que se llame igual`() {
        assertIs<Command.Exit>(Commands.parse("/exit", catalog("exit")))
    }

    @Test
    fun `lo que no empieza por barra es un prompt`() {
        assertEquals("qué hace /compact", assertIs<Command.Prompt>(Commands.parse("  qué hace /compact ", catalog())).text)
    }

    @Test
    fun `la ayuda lista los builtin y los skills`() {
        val help = Commands.help(catalog("release"))
        Commands.builtins.forEach { assertContains(help, it) }
        assertContains(help, "/release")
        assertContains(help, "hace release")
    }
}
