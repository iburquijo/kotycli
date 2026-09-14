# ADR 0002. El core no conoce ningún proveedor ni frontend

**Estado:** aceptado

## Contexto

El motivo del proyecto es no estar atado a un proveedor ni a una forma de pintar. Si el loop usa directamente tipos de un SDK, cambiar de proveedor es reescribir el loop. Si el loop hace `println`, añadir ACP es reescribir el loop.

## Decisión

- Modelo de mensajes propio (`Message`, `Block`, `StopReason`, `Completion`) en `core/`.
- Hacia abajo, la interfaz `Provider` con `stream` y `Capabilities`, para que el core adapte su comportamiento sin `if (provider == "x")`.
- Hacia arriba, un `Flow<AgentEvent>` etiquetado por agente. Los frontends son suscriptores.
- Bloques que solo entiende un proveedor (reasoning, compaction) viajan como `Block.Opaque(providerId, payload)` y se reenvían solo al mismo proveedor.
- Orden de adaptadores: `openai` (OpenAI-compatible, sin SDK), `copilot` (mismo wire con auth y headers propios), `anthropic` (opcional). Ver ADR 0012.
- Un test de arquitectura falla si aparece un tipo de wire o de SDK fuera de `providers/<nombre>/`, o un `println` fuera de `frontend/`.

## Alternativas descartadas

- **Usar el tool runner de un SDK como loop**: es el camino corto, pero es exactamente el acoplamiento que queremos evitar.
- **Librerías de agentes multi-proveedor (LangChain4j y similares)**: traen su propio loop y sus propias abstracciones. Queremos que el loop sea nuestro; son 2.000 líneas.
- **Solo OpenAI-compatible, sin interfaz**: hoy cubre casi todo, pero cierra la puerta a features específicas (caché de prompt, compactación nativa) cuando haya un proveedor directo.

## Consecuencias

- Cada feature de proveedor que queramos aprovechar necesita una `Capability` y una rama en el core. Se acepta, es el precio.
- El modelo neutral es el mínimo común: texto, tool use, tool result, opaco. Imágenes y documentos se añaden cuando hagan falta.
