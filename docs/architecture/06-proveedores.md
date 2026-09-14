# 06. Proveedores de modelo

Este es el punto por el que existe el proyecto: que cambiar de modelo sea cambiar una línea de configuración.

## Interfaz

```kotlin
interface LlmProvider {
    val id: String                                    // "anthropic", "openai-compat", ...
    val capabilities: Capabilities
    suspend fun complete(request: Request): Completion
    fun stream(request: Request): Flow<StreamEvent>   // TextDelta, ToolUseStart, ToolInputDelta, Done(Completion)
}

data class Request(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
    val maxTokens: Int,
)

data class Capabilities(
    val parallelToolCalls: Boolean,
    val promptCaching: Boolean,
    val nativeCompaction: Boolean,
    val allowsHistoryEdits: Boolean,      // false => ContextManager no poda, solo compacta
    val contextWindow: Int,
)
```

El loop solo usa `stream` (para pintar en tiempo real) y se queda con el `Completion` final. `complete` existe para tests y para subagentes sin UI.

## Traducción

Cada adaptador implementa dos funciones puras y bien testeables:

- `toProvider(Request) -> <tipos del SDK>`
- `fromProvider(<respuesta del SDK>) -> Completion`

Los bloques `Opaque` con `providerId == this.id` se deserializan y se reenvían. Los de otro `providerId` se descartan con un log de debug.

## Adaptador 1: Anthropic

Primer adaptador, porque es el que mejor conozco y porque su API de tools es la referencia (tool_use / tool_result por id, resultados paralelos en un solo mensaje).

- Dependencia: SDK Java oficial, `com.anthropic:anthropic-java`. Kotlin lo consume sin fricción.
- Cliente: `AnthropicOkHttpClient.fromEnv()` lee `ANTHROPIC_API_KEY`. Base URL configurable para gateways corporativos.
- Mapeo directo: `Text` -> `TextBlockParam`, `ToolUse` -> `ToolUseBlockParam`, `ToolResult` -> `ToolResultBlockParam`, bloques de thinking -> `Opaque`.
- Thinking: adaptativo (es el modo por defecto en los modelos actuales). No fijamos presupuestos de tokens de thinking.
- Caching: un breakpoint en el system prompt. Como el historial es append-only y la lista de tools es estable en la sesión, el prefijo se reutiliza solo.
- `stop_reason` -> `StopReason`: `end_turn`, `tool_use`, `max_tokens`, `refusal`, resto a `OTHER`.
- Errores: cadena de excepciones tipadas del SDK (rate limit y 5xx reintentan con backoff, 4xx no).

## Adaptador 2: OpenAI-compatible

Segundo adaptador y el que da la independencia real, porque el endpoint `/v1/chat/completions` con `tools` lo hablan OpenAI, Ollama, vLLM, LM Studio, OpenRouter, Azure y la mayoría de gateways corporativos.

- Sin SDK: cliente HTTP propio (`java.net.http.HttpClient` o Ktor client) + `kotlinx.serialization`. Menos dependencias y control total sobre el JSON.
- Diferencias que tiene que absorber el adaptador, no el loop:
  - Los tool results van como mensajes `role: tool` individuales, no como bloques en un mensaje user. `fromProvider`/`toProvider` hacen la conversión.
  - El streaming de argumentos de tool llega en fragmentos de JSON que hay que acumular.
  - No hay bloques de thinking estándar; lo que llegue en `reasoning_content` se guarda como `Opaque`.
- `Capabilities.parallelToolCalls` depende del modelo; se lee de configuración.

## Configuración

`~/.kotycli/config.json` (y override en `.kotycli/config.json` del proyecto):

```json
{
  "provider": "anthropic",
  "model": "claude-opus-5",
  "providers": {
    "anthropic": { "baseUrl": null },
    "local":     { "type": "openai-compat", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:32b" },
    "corp":      { "type": "openai-compat", "baseUrl": "https://llm-gw.corp/v1", "apiKeyEnv": "CORP_LLM_KEY" }
  },
  "subagentModel": null
}
```

Cambiar de proveedor: `kotycli --provider local`. Las claves nunca van en el fichero, solo el nombre de la variable de entorno.

## Regla de oro

Ningún tipo de `com.anthropic.*`, ni ningún JSON con forma de proveedor, sale del paquete `providers/<nombre>/`. Se comprueba con un test de arquitectura (ArchUnit o un `grep` en CI) que falla si aparece un import de proveedor fuera de su paquete.
