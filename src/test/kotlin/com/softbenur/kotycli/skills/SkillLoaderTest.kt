package com.softbenur.kotycli.skills

import com.softbenur.kotycli.config.Dirs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillLoaderTest {
    private fun skill(dir: Path, name: String, description: String, body: String) {
        dir.resolve("skills").resolve(name).createDirectories()
        dir.resolve("skills").resolve(name).resolve("SKILL.md")
            .writeText("---\nname: $name\ndescription: $description\n---\n\n$body\n")
    }

    private fun setup(): Pair<Dirs, Path> {
        val root = Files.createTempDirectory("kotycli-skills")
        val project = root.resolve("repo")
        project.resolve(".git").createDirectories()
        val dirs = Dirs(home = root.resolve("home"), project = project)
        skill(dirs.userAgentsDir, "commit-style", "Cómo escribir un commit", "Imperativo y en castellano.")
        skill(dirs.projectAgentsDir, "deploy", "Despliega a staging", "1. ./gradlew build")
        return dirs to project
    }

    @Test
    fun `carga usuario y proyecto, y los lista para el system prompt`() {
        val (dirs, project) = setup()
        val catalog = SkillLoader.load(dirs, project)

        assertEquals(listOf("commit-style", "deploy"), catalog.names())
        val section = catalog.promptSection()!!
        assertTrue(section.contains("- deploy — Despliega a staging — ${dirs.projectAgentsDir.resolve("skills/deploy/SKILL.md")}"), section)
    }

    @Test
    fun `el skill del subdirectorio mas cercano gana y el cuerpo se lee del disco`() {
        val (dirs, project) = setup()
        val sub = project.resolve("modulo")
        skill(sub.resolve(".agents"), "deploy", "Despliegue del módulo", "Solo este módulo.")

        val catalog = SkillLoader.load(dirs, sub)
        assertEquals("Solo este módulo.", catalog["deploy"]!!.body())

        // Editar el SKILL.md a mitad de sesión tiene efecto sin recargar el catálogo.
        catalog["deploy"]!!.path.writeText("---\nname: deploy\ndescription: x\n---\nOtra cosa.")
        assertEquals("Otra cosa.", catalog["deploy"]!!.body())
    }

    @Test
    fun `slash nombre se expande al cuerpo con los argumentos`() {
        val (dirs, project) = setup()
        val catalog = SkillLoader.load(dirs, project)

        assertEquals("1. ./gradlew build\n\nArgumentos: rama-x", catalog.expand("/deploy rama-x"))
        assertEquals("1. ./gradlew build", catalog.expand("/deploy"))
        assertNull(catalog.expand("/exit"))
        assertNull(catalog.expand("despliega esto"))
    }

    @Test
    fun `sin skills no hay seccion en el prompt`() {
        val root = Files.createTempDirectory("kotycli-skills-vacio")
        assertNull(SkillLoader.load(Dirs(home = root, project = root)).promptSection())
    }
}
