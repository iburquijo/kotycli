package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.testContext
import com.softbenur.kotycli.core.Message
import com.softbenur.kotycli.core.PermissionMode
import com.softbenur.kotycli.skills.Skill
import com.softbenur.kotycli.skills.SkillCatalog
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoopSessionTest {
    private val root = testContext(FakeProvider(mutableListOf()))

    @Test
    fun `describe usa lo que le da el bootstrap y si no, lo que sabe del contexto`() {
        assertEquals("proveedor: fake", LoopSession(root, describer = { "proveedor: fake" }).describe())
        val fallback = LoopSession(root).describe()
        assertContains(fallback, "modelo m")
        assertContains(fallback, PermissionMode.YOLO.cli)
    }

    @Test
    fun `reload aplica skills, system prompt y modo de permisos sin tocar el historial`() {
        root.messages += Message.user("algo dicho antes")
        val nuevo = SkillCatalog(listOf(Skill("release", "publica", Path.of("SKILL.md"))))
        val session = LoopSession(root, reloader = {
            Reload(nuevo, "prompt nuevo", PermissionMode.ACCEPT_EDITS, "Recargado: 1 skills")
        })

        assertEquals("Recargado: 1 skills", session.reload())
        assertEquals(listOf("release"), session.skills.names())
        assertEquals("prompt nuevo", root.config.systemPrompt)
        assertEquals(PermissionMode.ACCEPT_EDITS, root.config.permissionMode)
        assertEquals(1, root.messages.size)
    }

    @Test
    fun `un config roto no deja la sesion a medias`() {
        val session = LoopSession(root, skills = SkillCatalog(listOf(Skill("viejo", "d", Path.of("SKILL.md"))))) {
            throw IllegalArgumentException("No se pudo leer config.json")
        }

        assertContains(session.reload(), "No se pudo leer config.json")
        assertEquals(listOf("viejo"), session.skills.names())
        assertEquals("test", root.config.systemPrompt)
    }

    @Test
    fun `sin reloader la sesion lo dice en vez de fingir`() = runTest {
        assertContains(LoopSession(root).reload(), "no puede recargar")
    }

    @Test
    fun `compact delega en el ContextManager del contexto raiz`() = runTest {
        val session = LoopSession(root)
        assertIs<com.softbenur.kotycli.core.CompactResult.NothingToDo>(session.compact())
    }
}
