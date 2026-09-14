# 08. Frontends

Tres `main` distintos consumiendo el mismo core. Añadir un frontend nunca toca el loop. La frontera es `Flow<AgentEvent>` hacia arriba y comandos (prompt, cancelar, respuesta a permiso) hacia abajo.

## La decisión de render más importante: append-only

El transcript fluye hacia arriba como un log. Solo la zona del prompt y una línea de estado están vivas. **Nunca pantalla completa, nunca framework de repintado.** En Windows es la diferencia entre escribir unos KB por segundo y repintar miles de celdas por frame a través del pipeline de consola. Es el origen directo del problema con otras TUIs y no se negocia (ADR 0008).

## TUI (por defecto)

Terminal nativo con JLine para el prompt, el historial y el completado.

Cómo se pinta cada evento:

| Evento | Render |
|--------|--------|
| `TextDelta` | Se escribe según llega. Sin buffer, sin markdown pesado; negrita y código inline como mucho |
| `ToolStart` | `● bash  mvn -q test -Dtest=X` en cian, con el `description` si la tool lo trae |
| `ToolEnd` | `  └ 4 passed, 1 failed (2.1s)` en tenue. Resumen de una línea; el resultado completo va al log |
| `PermissionAsk` | Bloque con el comando en amarillo, aviso en rojo si matchea patrón destructivo, y `[s] permitir · [S] siempre en esta sesión · [n] denegar` |
| `SubagentStart` / eventos del hijo / `SubagentEnd` | Indentado con `│`, tools del hijo en tenue, cierre con `✓ 4 tools · 41.2k tokens quemados · 380 devueltos` |
| `Compacted` | Línea de separación con `antes -> después` tokens |
| Fin de turn | Separador con `12.4k tokens · 0:31` |

Comandos `/`:

| Comando | Qué hace |
|---------|----------|
| `/compact` | Resume la conversación y libera contexto |
| `/config` | Ver y cambiar proveedor, modelo y modo de permisos en caliente |
| `/copy` | Copia la última respuesta al portapapeles |
| `/edit` | Vuelca el transcript a un fichero temporal y abre `$EDITOR`. Navegar con Emacs de verdad, gratis |
| `/reload` | Reescanea skills y agentes |
| `/<skill>` | Invoca un skill con argumentos |

Fase 1: completado clásico con Tab de JLine. El panel flotante filtrado en vivo bajo el prompt llega después: es puro chrome sobre el mismo completer.

Pager (opcional, después): con `Esc` se entra al alternate buffer y se navega el transcript con teclas vim (`j/k`, `^d/^u`, `gg/G`, `/`, `q`). Solo repinta al pulsar tecla, cero coste durante el streaming. `/edit` es el atajo previo y más barato.

## `--plain`

Modo terminal tonto: sin ANSI, sin raw mode, sin JLine. Lee líneas de stdin, escribe texto a stdout. Funciona en comint, `M-x shell`, pipes y CI.

- `PermissionAsk` se imprime como pregunta `[s/n]` y se lee una línea. Sin TTY o con `--yes`/`--mode yolo`, se resuelve según el modo; por defecto `Deny`.
- Un prompt como argumento (`kotycli --plain "arregla X"`) ejecuta un turn y sale. Sin argumento, bucle de líneas.
- Los eventos de subagente se reducen a una línea de inicio y otra de fin.

## ACP (`--acp`)

Agent Client Protocol: JSON-RPC 2.0 sobre stdin/stdout. Los clientes ya existen y los mantienen otros: agent-shell y agent-ide en Emacs, Zed, Neovim. Nosotros implementamos solo el lado agente, unos cientos de líneas de Kotlin sobre eventos que ya existen.

Mapeo:

| Nuestro | ACP |
|---------|-----|
| Arranque | `initialize` (capacidades: prompt de texto, permisos) |
| Nueva conversación | `session/new` -> `AgentContext` raíz |
| Prompt del usuario | `session/prompt` -> `runLoop`; la respuesta llega cuando termina el turn con `stopReason` |
| `TextDelta` | `session/update` con `agent_message_chunk` |
| `ToolStart` / `ToolEnd` | `session/update` con `tool_call` y `tool_call_update` (estado, título, salida resumida) |
| `PermissionAsk` | `session/request_permission` con opciones allow once / allow always / reject; la respuesta completa el `CompletableDeferred` |
| Ctrl+C del cliente | `session/cancel` -> cancelación del `Job` |
| `SubagentStart` / hijo | `session/update` etiquetado; el cliente decide si agrupa |

Reglas duras:

- **stdout es del protocolo.** Ni un `println` en todo el proceso. Logs a fichero, siempre.
- Los `Ask` sin respuesta del cliente en un tiempo razonable se resuelven como `Deny`.
- El `Flow<AgentEvent>` es el mismo objeto que consume la TUI; ACP es otro suscriptor.

## Lo que ningún frontend hace

- No decide permisos: pinta la pregunta y devuelve la respuesta.
- No toca el historial: `/compact` es un comando al core, no una manipulación local.
- No conoce proveedores: `/config` cambia configuración y el core reconstruye el `Provider`.
