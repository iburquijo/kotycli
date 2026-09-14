package com.softbenur.kotycli.agents

import com.softbenur.kotycli.config.Dirs
import com.softbenur.kotycli.skills.Frontmatter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrontmatterTest {
    @Test
    fun `campos, lista y cuerpo`() {
        val fm = Frontmatter.parse(
            """
            ---
            name: reviewer
            description: "Revisa un diff"
            tools: [read, bash]
            ---

            # Revisor

            Lee el diff.
            """.trimIndent()
        )
        assertEquals("reviewer", fm["name"])
        assertEquals("Revisa un diff", fm["description"])
        assertEquals(listOf("read", "bash"), fm.list("tools"))
        assertEquals("# Revisor\n\nLee el diff.", fm.body)
    }

    @Test
    fun `sin frontmatter todo es cuerpo`() {
        val fm = Frontmatter.parse("# Solo markdown\n")
        assertNull(fm["name"])
        assertEquals("# Solo markdown", fm.body)
    }
}

class AgentTypeLoaderTest {
    private fun dirs(root: Path) = Dirs(home = root.resolve("home"), project = root.resolve("proyecto"))

    private fun writeRole(dir: Path, file: String, text: String) {
        dir.resolve("agents").createDirectories()
        dir.resolve("agents").resolve(file).writeText(text)
    }

    @Test
    fun `el proyecto pisa al usuario y el usuario a los builtin`() {
        val root = Files.createTempDirectory("kotycli-agents")
        val d = dirs(root)
        writeRole(d.userAgentsDir, "reviewer.md", "---\ndescription: del usuario\ntools: read\n---\nRevisa.")
        writeRole(d.projectAgentsDir, "reviewer.md", "---\ndescription: del proyecto\ntools: read, bash\n---\nRevisa mejor.")
        writeRole(d.projectAgentsDir, "explorer.md", "---\ndescription: explorador propio\n---\nBusca a mi manera.")

        val types = AgentTypeLoader.load(d)

        assertEquals("del proyecto", types.getValue("reviewer").description)
        assertEquals(setOf("read", "bash"), types.getValue("reviewer").tools)
        assertEquals("Busca a mi manera.", types.getValue("explorer").systemPrompt)
        // Sin `tools` hereda el toolset del rol que pisa, no el de implementor.
        assertEquals(AgentType.EXPLORER.tools, types.getValue("explorer").tools)
        assertTrue("implementor" in types)
    }

    @Test
    fun `un rol sin description o sin cuerpo se ignora`() {
        val root = Files.createTempDirectory("kotycli-agents")
        val d = dirs(root)
        writeRole(d.projectAgentsDir, "mudo.md", "---\nname: mudo\n---\nSin descripción.")
        writeRole(d.projectAgentsDir, "vacio.md", "---\ndescription: sin cuerpo\n---\n")

        assertEquals(AgentType.BUILTIN.map { it.name }.toSet(), AgentTypeLoader.load(d).keys)
    }
}
