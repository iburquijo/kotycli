# ADR 0009. Interceptores sí, sistema de hooks no

**Estado:** aceptado

## Contexto

Permisos, logging de tool calls, guardas de path y truncado de resultados necesitan un punto de corte alrededor de la ejecución de cada tool. Otras herramientas lo resuelven con hooks configurables por fichero que ejecutan scripts externos.

## Decisión

- Una interfaz `ToolInterceptor` con `before(ctx, tool, call): ToolResult?` (null = continuar, un resultado = cortocircuitar) y `after(ctx, tool, call, result): ToolResult`.
- Una lista ordenada de interceptores en el `AgentContext`: `PathGuard`, `Permissions`, `ToolLog`, `Truncate`. Los subagentes heredan la misma lista.
- No hay hooks configurables por fichero ni scripts externos en el ciclo de vida de una tool.

## Alternativas descartadas

- **Sistema de hooks por configuración**: la extensibilidad por config es para herramientas con miles de usuarios. Para uno, el código es la config: añadir un comportamiento es añadir una clase.
- **Lógica inline en el dispatcher**: funciona para uno, se vuelve ilegible con cuatro.
- **Lógica dentro de cada tool**: se duplica y cada tool tiene que conocer la UI.

## Consecuencias

- El "sistema de hooks" sale gratis del mismo punto de corte que los permisos.
- Si algún día hacen falta hooks externos, son un `ToolInterceptor` más que ejecuta un script. La interfaz no cambia.
