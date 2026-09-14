package com.softbenur.kotycli.tools

import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.Block
import com.softbenur.kotycli.core.ToolDefinition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpClient
import java.nio.file.Path
import kotlin.time.Duration

/** Texto que ve el modelo por cada campo del input de una tool. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val text: String)

interface Tool {
    val name: String
    /** Lo que ve el modelo; aquí se juega la mitad de la calidad. */
    val description: String
    /** Generado desde la data class @Serializable del input. */
    val inputSchema: JsonObject
    /** true => paralelizable y normalmente sin pedir permiso. */
    val readOnly: Boolean
    val timeout: Duration
    /** Campos del input que son rutas; los canonicaliza y vigila el interceptor `PathGuard`. */
    val pathFields: List<String> get() = emptyList()

    suspend fun execute(input: JsonObject, ctx: ToolContext): Block.ToolResult

    fun definition() = ToolDefinition(name, description, inputSchema)
}

/** Tool con input tipado: deserializa el JSON a `I`, genera el schema y convierte errores de parseo en `isError`. */
abstract class TypedTool<I : Any>(private val serializer: KSerializer<I>) : Tool {
    override val inputSchema: JsonObject by lazy { SchemaGen.schema(serializer.descriptor) }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): Block.ToolResult {
        val parsed = try {
            json.decodeFromJsonElement(serializer, input)
        } catch (e: SerializationException) {
            return ctx.error("Input inválido para $name: ${e.message?.lineSequence()?.first()}")
        } catch (e: IllegalArgumentException) {
            return ctx.error("Input inválido para $name: ${e.message}")
        }
        return run(parsed, ctx)
    }

    abstract suspend fun run(input: I, ctx: ToolContext): Block.ToolResult

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}

/** Lo que comparten todas las tools de una sesión: working dir, ficheros leídos, el único HttpClient, el shell. */
class ToolEnv(
    val workDir: Path,
    val fileTracker: FileTracker = FileTracker(),
    val http: HttpClient? = null,
    val shell: Shell = Shell.detect(),
    /** Rutas fuera del working dir permitidas explícitamente (`--allow-path`). */
    val allowedPaths: List<Path> = emptyList(),
) {
    /** Resuelve una ruta del input contra el working dir y la normaliza. Nunca compara strings. */
    fun resolve(path: String): Path = workDir.resolve(path).toAbsolutePath().normalize()
}

class ToolContext(val agent: AgentContext, val callId: String) {
    val env: ToolEnv get() = agent.env
    val workDir: Path get() = env.workDir
    val fileTracker: FileTracker get() = env.fileTracker

    fun resolve(path: String): Path = env.resolve(path)
    fun ok(content: String) = Block.ToolResult(callId, content)
    fun error(content: String) = Block.ToolResult(callId, content, isError = true)
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    operator fun get(name: String): Tool? = byName[name]
    fun names(): List<String> = byName.keys.toList()
    fun all(): List<Tool> = byName.values.toList()
    fun definitions(): List<ToolDefinition> = byName.values.map { it.definition() }
    fun restrictedTo(names: Set<String>): ToolRegistry = ToolRegistry(byName.values.filter { it.name in names })
}
