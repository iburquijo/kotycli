# ADR 0008. TUI append-only, nunca pantalla completa

**Estado:** aceptado

## Contexto

Las TUIs de pantalla completa de otras herramientas se arrastran en el pipeline de consola de Windows: repintan miles de celdas por frame y el resultado es un agente que va a tirones. Es el origen directo del proyecto.

## Decisión

- El transcript fluye hacia arriba como un log. El streaming del modelo se escribe según llega. Nada se repinta.
- Las únicas zonas vivas son el prompt (JLine) y una línea de estado.
- Cada tool call es una línea con su resultado colapsado a un resumen de una línea. El resultado completo va al log de tools.
- El menú de comandos `/` y el pager vim son zonas vivas pequeñas y puntuales, no un framework de repintado. El pager entra al alternate buffer y solo repinta al pulsar tecla.
- `--plain` como salida de emergencia: sin ANSI, sin raw mode, funciona en comint y pipes.

## Alternativas descartadas

- **Frameworks de TUI de pantalla completa (Mosaic, Lanterna y similares)**: el problema que queremos evitar.
- **Markdown renderizado completo**: implica bufferizar y repintar. Negrita y código inline como mucho.

## Consecuencias

- No hay scroll propio: el del terminal. Para navegar el transcript, `/edit` al `$EDITOR` primero y el pager después.
- La TUI es pequeña. Casi toda la lógica de "qué mostrar" es un `when` sobre `AgentEvent`.
