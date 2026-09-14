# ADR 0005. Permisos decididos por el harness, como interceptor

**Estado:** aceptado

## Contexto

El modelo puede ejecutar comandos y modificar ficheros. Decirle en el prompt lo que no debe hacer no es un control: el control tiene que estar en el código que ejecuta la acción.

## Decisión

- Un interceptor `Permissions` en el dispatcher (ADR 0009) consulta `PermissionPolicy.decide(ctx, tool, input) -> Allow | Ask | Deny` antes de ejecutar cualquier tool.
- Tres modos: `default` (lecturas libres, escrituras y bash preguntan), `accept-edits`, `yolo`.
- Reglas configurables por tool y patrón (`bash(git status*)`, `create(/etc/**)`), con precedencia deny > allow > ask. "Permitir siempre en esta sesión" como respuesta, persistible como regla.
- Un interceptor `PathGuard` canonicaliza rutas contra el working dir y deniega las que se salen.
- Sin frontend que responda (`--plain` sin TTY, timeout), `Ask` se resuelve como `Deny`.
- Los subagentes heredan la política del padre y no pueden relajarla.

## Alternativas descartadas

- **Sandbox de kernel (contenedores, seccomp, landlock)**: dependiente del SO, que es justo lo que queremos evitar. Puede añadirse como capa opcional más adelante.
- **Confiar en el prompt**: no es un control.
- **Permisos dentro de cada tool**: se duplica la lógica y cada tool tiene que saber de UI. Un interceptor lo hace una vez.

## Consecuencias

- Una tool denegada devuelve un `ToolResult` de error al modelo, que puede proponer otra cosa. No se aborta el turn.
- El modo `yolo` existe y está documentado como peligroso. Para CI y contenedores desechables.
- La pregunta al usuario es un `AgentEvent.PermissionAsk` con un `CompletableDeferred`: la TUI, `--plain` y ACP lo resuelven cada uno a su manera.
