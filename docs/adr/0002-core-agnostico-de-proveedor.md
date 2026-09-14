# ADR 0002. El core no conoce ningún proveedor

**Estado:** aceptado

## Contexto

El motivo del proyecto es no estar atado a un proveedor. Si el loop usa directamente tipos de un SDK, cambiar de proveedor es reescribir el loop.

## Decisión

- Modelo de mensajes propio (`Message`, `Block`, `StopReason`, `Completion`) en `core/`.
- Interfaz `LlmProvider` con `complete` y `stream`, más `Capabilities` para que el core adapte su comportamiento (poda de historial, paralelismo de tools) sin `if (provider == "x")`.
- Bloques que solo entiende un proveedor (thinking, compaction) viajan como `Block.Opaque(providerId, payload)` y se reenvían solo al mismo proveedor.
- Primer adaptador: Anthropic con el SDK Java oficial. Segundo: OpenAI-compatible con HTTP propio, que cubre Ollama, vLLM, OpenRouter, Azure y gateways corporativos.
- Un test de arquitectura falla si aparece un import de proveedor fuera de `providers/<nombre>/`.

## Alternativas descartadas

- **Usar el tool runner del SDK de Anthropic como loop**: es el camino corto, pero es exactamente el acoplamiento que queremos evitar.
- **Librerías de agentes multi-proveedor (LangChain4j y similares)**: traen su propio loop y sus propias abstracciones. Queremos que el loop sea nuestro.
- **Solo OpenAI-compatible**: perderíamos features específicas (caché de prompt, compactación nativa) del proveedor que más vamos a usar.

## Consecuencias

- Cada feature de proveedor que queramos aprovechar necesita una `Capability` y una rama en el core. Se acepta, es el precio.
- El modelo neutral es el mínimo común: texto, tool use, tool result, opaco. Imágenes y documentos se añaden cuando hagan falta.
