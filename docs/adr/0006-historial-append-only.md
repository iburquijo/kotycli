# ADR 0006. Historial de mensajes append-only

**Estado:** aceptado

## Contexto

Editar mensajes ya enviados rompe la caché de prefijo de todos los proveedores que la tienen, y algunos proveedores vinculan bloques de razonamiento al historial exacto que los produjo, rechazando peticiones con historial reescrito.

## Decisión

- El core nunca modifica un `Message` que ya se envió al proveedor. Solo añade.
- La gestión de contexto se hace por compactación: se sustituye un prefijo por un resumen y se conservan las últimas rondas. Eso es un historial nuevo, no una edición del viejo.
- La poda de resultados antiguos de tool (sustituirlos por un marcador) solo se aplica si `Capabilities.allowsHistoryEdits` es `true`. Si no, se salta directamente a compactar.
- Tras una cancelación a mitad de ronda, se cierra la ronda añadiendo `ToolResult` de error para cada llamada pendiente, nunca borrando el `ToolUse`.

## Consecuencias

- El historial crece hasta que se compacta. La compactación tiene que funcionar bien porque es la única válvula en algunos proveedores.
- Las reglas de "reemplazar prefijo por resumen" se implementan una vez en `ContextManager` y se testean con un proveedor fake.
