# ADR 0005. Permisos decididos por el harness

**Estado:** aceptado

## Contexto

El modelo puede ejecutar comandos y modificar ficheros. Decirle en el prompt lo que no debe hacer no es un control: el control tiene que estar en el código que ejecuta la acción.

## Decisión

- `PermissionPolicy.decide(session, tool, input) -> Allow | Ask | Deny` se consulta en el dispatcher antes de ejecutar cualquier tool.
- Tres modos: `default` (lecturas libres, escrituras y bash preguntan), `accept-edits`, `yolo`.
- Reglas configurables por tool y patrón (`bash(git status*)`, `write(/etc/**)`), con precedencia deny > allow > ask. Las decisiones aceptadas por el usuario se pueden persistir como reglas.
- Sandbox de rutas: las tools de fichero solo operan bajo el `cwd` del proyecto salvo regla explícita.
- Sin TTY (`--print`), `Ask` se convierte en `Deny`.
- Los subagentes heredan la política del padre y no pueden relajarla.

## Alternativas descartadas

- **Sandbox de kernel (contenedores, seccomp, landlock)**: dependiente del SO, que es justo lo que queremos evitar. Puede añadirse como capa opcional más adelante.
- **Confiar en el prompt**: no es un control.

## Consecuencias

- Una tool denegada devuelve un `ToolResult` de error al modelo, que puede proponer otra cosa. No se aborta el turn.
- El modo `yolo` existe y está documentado como peligroso. Para CI y contenedores desechables.
