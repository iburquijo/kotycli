package com.softbenur.kotycli.tools

import com.softbenur.kotycli.core.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CreateInput(
    @Description("Ruta del fichero nuevo, relativa al working dir") val path: String,
    @Description("Contenido completo del fichero") val content: String,
)

/** Crea un fichero nuevo. Falla si existe: nada se sobreescribe sin haberse leído (ADR 0003). */
class CreateTool : TypedTool<CreateInput>(CreateInput.serializer()) {
    override val name = "create"
    override val readOnly = false
    override val timeout: Duration = 10.seconds
    override val pathFields = listOf("path")
    override val description = """
        Crea un fichero nuevo con el contenido dado, creando los directorios intermedios.
        Falla si el fichero ya existe: para modificar uno existente usa `edit`.
    """.trimIndent()

    override suspend fun run(input: CreateInput, ctx: ToolContext): Block.ToolResult = withContext(Dispatchers.IO) {
        val path = ctx.resolve(input.path)
        if (Files.exists(path)) return@withContext ctx.error("${input.path} ya existe. Léelo con `read` y modifícalo con `edit`, o bórralo primero con `bash`")
        path.parent?.let { Files.createDirectories(it) }
        try {
            Files.writeString(path, input.content, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            return@withContext ctx.error("${input.path} ya existe")
        }
        ctx.fileTracker.record(path)
        ctx.ok("Creado ${input.path} (${input.content.lines().size} líneas)")
    }
}
