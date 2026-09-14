# 06. Proveedores de modelo

Este es el punto por el que existe el proyecto: que cambiar de modelo sea cambiar una línea de configuración. Copilot en el trabajo, Ollama en casa, y cualquier endpoint OpenAI-compatible como plan B permanente.

## Interfaz

```kotlin
interface Provider {
    val id: String                                    // "copilot", "openai", "anthropic", ...
    val capabilities: Capabilities
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
    val allowsHistoryEdits: Boolean,      // false => ContextManager no poda, solo compacta
    val contextWindow: Int,
)
```

El loop solo usa `stream` y se queda con el `Completion` final. Un `complete` no-streaming es una extensión sobre `stream` para tests.

## Traducción

Cada adaptador implementa dos funciones puras y bien testeables:

- `toWire(Request) -> JSON del proveedor`
- `fromWire(respuesta del proveedor) -> Completion / StreamEvent`

Los bloques `Opaque` con `providerId == this.id` se reenvían. Los de otro `providerId` se descartan con un log de debug.

## Un solo `HttpClient`

Todos los adaptadores (y la tool `fetch`) usan el mismo `java.net.http.HttpClient` construido una vez en `http/`, con el truststore corporativo cargado y el proxy resuelto. Ver [09-red-corporativa](09-red-corporativa.md). Ningún adaptador crea su propio cliente HTTP.

## Adaptadores

### `openai`: OpenAI-compatible genérico

El primero que se implementa, porque es el entorno de desarrollo (Ollama en casa) y el plan B en el trabajo (proxies, gateways corporativos, vLLM, LM Studio, OpenRouter, Azure). Wire `POST /v1/chat/completions` con `tools` y `stream: true`.

- Sin SDK: `HttpClient` común + `kotlinx.serialization`. Control total del JSON y una dependencia menos.
- Diferencias que absorbe el adaptador, no el loop:
  - Los tool results van como mensajes `role: tool` individuales. `toWire` expande nuestro único mensaje `USER` con N `ToolResult` en N mensajes `tool`; `fromWire` hace la inversa.
  - Los argumentos de tool llegan en fragmentos de JSON por SSE que hay que acumular por `index` hasta `finish_reason`.
  - `finish_reason`: `stop` -> `END_TURN`, `tool_calls` -> `TOOL_USE`, `length` -> `MAX_TOKENS`, `content_filter` -> `REFUSAL`.
  - `reasoning_content` u otros campos no estándar se guardan como `Opaque`.
- `Capabilities` se leen de la configuración por modelo, porque varían entre servidores.

### `copilot`: GitHub Copilot

Mismo wire OpenAI-compatible, con tres cosas encima:

1. **Device flow OAuth** con el client ID de VS Code para obtener el token de GitHub. Se guarda en `~/.kotycli/auth/github.json`.
2. **Exchange** de ese token por un token corto de Copilot (`/copilot_internal/v2/token`), con caché y refresco automático antes de que caduque.
3. **Headers** `Editor-Version`, `Editor-Plugin-Version` y `Copilot-Integration-Id`, sin los cuales el endpoint rechaza la petición.

El adaptador es `OpenAiProvider` con un `Authenticator` distinto y unos headers extra. No hay lógica de wire duplicada.

Riesgo asumido: la API no está documentada públicamente y está sujeta a la política de la empresa y a los términos de la licencia de Copilot. Por eso el adaptador `openai` es el plan B permanente y no una opción secundaria. Ver ADR 0012.

### `anthropic`: opcional

Tercero, para quien tenga clave directa. SDK Java oficial (`com.anthropic:anthropic-java`) o wire propio sobre el `HttpClient` común; se decide al implementarlo según qué encaje mejor con el proxy. Mapeo directo: `tool_use`/`tool_result` por id, resultados paralelos en un único mensaje `user` (es nuestro modelo neutral, así que la traducción es casi identidad), bloques de thinking como `Opaque`, un breakpoint de caché en el system prompt.

## Configuración

`~/.kotycli/config.json`, con override en `.kotycli/config.json` del proyecto:

```json
{
  "provider": "copilot",
  "model": "claude-sonnet-4.6",
  "providers": {
    "copilot":  { "type": "copilot" },
    "local":    { "type": "openai", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:32b" },
    "corp":     { "type": "openai", "baseUrl": "https://llm-gw.corp/v1", "apiKeyEnv": "CORP_LLM_KEY" },
    "anthropic":{ "type": "anthropic", "apiKeyEnv": "ANTHROPIC_API_KEY" }
  },
  "subagentModel": null
}
```

Cambiar de proveedor: `kotycli --provider local`. Las claves nunca van en el fichero, solo el nombre de la variable de entorno.

## Regla de oro

Ningún JSON con forma de proveedor, ni ningún tipo de SDK, sale del paquete `providers/<nombre>/`. Un test de arquitectura en CI falla si aparece un import de proveedor fuera de su paquete.
