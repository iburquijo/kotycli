package dev.kotycli

import dev.kotycli.core.AgentConfig
import dev.kotycli.core.AgentContext
import dev.kotycli.core.AgentEvent
import dev.kotycli.core.Block
import dev.kotycli.core.Budget
import dev.kotycli.core.Completion
import dev.kotycli.core.Message
import dev.kotycli.core.PermissionMode
import dev.kotycli.core.Role
import dev.kotycli.core.StopReason
import dev.kotycli.core.ToolInterceptor
import dev.kotycli.core.Usage
import dev.kotycli.providers.Capabilities
import dev.kotycli.providers.Provider
import dev.kotycli.providers.ProviderException
import dev.kotycli.providers.Request
import dev.kotycli.providers.StreamEvent
import dev.kotycli.tools.Shell
import dev.kotycli.tools.Tool
import dev.kotycli.tools.ToolContext
import dev.kotycli.tools.ToolEnv
import dev.kotycli.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Proveedor con guion: devuelve las completions en orden y graba las requests que recibe. */
class FakeProvider(
    private val script: MutableList<() -> Completion>,
    override val capabilities: Capabilities = Capabilities(contextWindow = 100_000),
) : Provider {
    override val id = "fake"
    val requests = mutableListOf<Request>()

    override fun stream(request: Request): Flow<StreamEvent> = flow {
        requests += request
        val next = script.removeFirstOrNull() ?: throw ProviderException("guion agotado")
        val completion = next()
        completion.message.text.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
        emit(StreamEvent.Done(completion))
    }

    companion object {
        fun text(text: String) = { Completion(Message.assistant(text), StopReason.END_TURN, Usage(10, 5)) }
        fun toolCall(id: String, name: String, input: JsonObject, text: String? = null) = {
            val blocks = listOfNotNull(text?.let { Block.Text(it) }, Block.ToolUse(id, name, input))
            Completion(Message(Role.ASSISTANT, blocks), StopReason.TOOL_USE, Usage(10, 5))
        }
        fun failing(message: String) = { throw ProviderException(message) }
    }
}

/** Tool de prueba que devuelve lo que se le pide o se bloquea hasta que la cancelen. */
class EchoTool(
    override val name: String = "echo",
    override val readOnly: Boolean = true,
    private val block: Boolean = false,
    private val fail: Boolean = false,
) : Tool {
    override val description = "eco"
    override val inputSchema = buildJsonObject { put("type", "object") }
    override val timeout: Duration = 5.seconds
    val calls = mutableListOf<JsonObject>()

    override suspend fun execute(input: JsonObject, ctx: ToolContext): Block.ToolResult {
        calls += input
        if (fail) throw IllegalStateException("boom")
        if (block) awaitCancellation()
        return ctx.ok("eco: ${input["text"]?.toString()?.trim('"').orEmpty()}")
    }
}

fun testContext(
    provider: Provider,
    tools: List<Tool> = listOf(EchoTool()),
    interceptors: List<ToolInterceptor> = emptyList(),
    workDir: Path = Files.createTempDirectory("kotycli-test"),
    mode: PermissionMode = PermissionMode.YOLO,
    maxIterations: Int = 10,
    budget: Budget = Budget(),
): AgentContext = AgentContext(
    config = AgentConfig(systemPrompt = "test", model = "m", maxIterationsPerTurn = maxIterations, permissionMode = mode),
    provider = provider,
    tools = ToolRegistry(tools),
    interceptors = interceptors,
    budget = budget,
    env = ToolEnv(workDir = workDir.toRealPath(), shell = Shell.detect()),
)

/** Suscribe un colector de eventos antes de arrancar el loop. Devuelve la lista viva y el job. */
suspend fun CoroutineScope.collectEvents(ctx: AgentContext): Pair<MutableList<AgentEvent>, Job> {
    val events = mutableListOf<AgentEvent>()
    val job = launch { ctx.events.collect { events += it } }
    yield() // que el colector se suscriba antes de que el loop emita nada
    return events to job
}

fun json(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
