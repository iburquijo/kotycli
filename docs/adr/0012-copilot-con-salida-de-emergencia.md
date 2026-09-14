# ADR 0012. Copilot como proveedor principal, OpenAI-compatible como plan B permanente

**Estado:** aceptado

## Contexto

En el trabajo el modelo disponible es GitHub Copilot. Su API de chat habla el wire OpenAI-compatible pero con autenticación propia (device flow con el client ID de VS Code, exchange a un token corto) y headers específicos (`Editor-Version`, `Copilot-Integration-Id`). No está documentada públicamente, está sujeta a la política de la empresa y a los términos de la licencia, y puede cambiar sin aviso.

## Decisión

- Adaptador `copilot` implementado como `OpenAiProvider` con un `Authenticator` y headers distintos. Sin lógica de wire duplicada.
- Adaptador `openai` genérico como **plan B permanente**, no como opción secundaria: cualquier endpoint `/v1/chat/completions` (Ollama en casa, gateways corporativos, vLLM, OpenRouter, Azure).
- Orden de implementación: `openai` primero (v1, contra Ollama), `copilot` después (v2). El desarrollo nunca depende de Copilot.
- El token de GitHub se guarda en `~/.kotycli/auth/`, nunca en config ni en el repo.

## Riesgo asumido

Que la empresa o GitHub cierren o cambien el acceso. Si pasa, se cambia `provider` en la config y se sigue trabajando. Ese es el motivo por el que la interfaz `Provider` existe (ADR 0002): el coste de que el plan A desaparezca es una línea de configuración.

## Alternativas descartadas

- **Copilot como único proveedor**: dependencia total de una API no documentada.
- **Solo proveedores con API pública**: en el trabajo no hay otro. El harness tiene que funcionar donde estamos, no donde nos gustaría estar.

## Consecuencias

- El adaptador `copilot` tiene tests contra fixtures grabadas, no contra la API real, para que CI no dependa de credenciales.
- Un cambio en la API de Copilot rompe un paquete, no el proyecto.
