package dev.kotycli.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaGenTest {
    @Serializable
    data class Sample(
        @Description("la ruta") val path: String,
        val count: Int? = null,
        val flag: Boolean = false,
        val tags: List<String> = emptyList(),
    )

    @Test
    fun `genera tipos, descripciones y required desde el descriptor`() {
        val schema = SchemaGen.schema(Sample.serializer().descriptor)
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("la ruta", props["path"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals("integer", props["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(props["count"]!!.jsonObject["description"])
        assertEquals("boolean", props["flag"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", props["tags"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["tags"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("path"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `las tools reales exponen sus campos`() {
        val edit = EditTool().inputSchema
        assertEquals(setOf("path", "old_string", "new_string", "replace_all"), edit["properties"]!!.jsonObject.keys)
        assertEquals(listOf("path", "old_string", "new_string"), edit["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("command"), BashTool(Shell.detect(), java.nio.file.Path.of(".")).inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
