package dev.kotycli.tools

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema a partir del `SerialDescriptor` de una data class `@Serializable`.
 * Un solo sitio de verdad: cambiar un campo cambia el schema, la deserialización y la descripción.
 */
object SchemaGen {
    fun schema(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val child = descriptor.getElementDescriptor(i)
                val description = descriptor.getElementAnnotations(i).filterIsInstance<Description>().firstOrNull()?.text
                put(name, typeSchema(child, description))
            }
        }
        val required = (0 until descriptor.elementsCount)
            .filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }
            .map { descriptor.getElementName(it) }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private fun typeSchema(d: SerialDescriptor, description: String?): JsonObject = buildJsonObject {
        when (val kind = d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            SerialKind.ENUM -> {
                put("type", "string")
                put("enum", buildJsonArray { for (i in 0 until d.elementsCount) add(kotlinx.serialization.json.JsonPrimitive(d.getElementName(i))) })
            }
            StructureKind.LIST -> {
                put("type", "array")
                put("items", typeSchema(d.getElementDescriptor(0), null))
            }
            StructureKind.MAP -> {
                put("type", "object")
                put("additionalProperties", typeSchema(d.getElementDescriptor(1), null))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val nested = schema(d)
                nested.forEach { (k, v) -> put(k, v) }
            }
            else -> put("type", "string").also { _ -> put("x-kind", kind.toString()) }
        }
        if (description != null) put("description", description)
    }
}
