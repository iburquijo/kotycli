# ADR 0010. Emacs y otros editores vía ACP, no vía plugin propio

**Estado:** aceptado

## Contexto

Queremos usar el harness desde Emacs sin mantener un frontend por editor. El Agent Client Protocol (JSON-RPC sobre stdio) ya tiene clientes mantenidos por otros: agent-shell y agent-ide en Emacs, Zed, Neovim.

## Decisión

- Implementar solo el lado agente de ACP como un tercer frontend (`--acp`): `initialize`, `session/new`, `session/prompt`, `session/cancel`, `session/update`, `session/request_permission`.
- Es un suscriptor más del `Flow<AgentEvent>`. Unos cientos de líneas de Kotlin sobre eventos que ya existen.
- stdout es del protocolo: ni un `println` en todo el proceso; logs a fichero.

## Alternativas descartadas

- **Modo elisp propio**: un frontend más que mantener, y sin él Zed y Neovim no existen.
- **Solo `--plain` dentro de comint**: funciona como salida de emergencia, pero sin permisos interactivos decentes ni estructura de tool calls.

## Consecuencias

- Los `Ask` de permisos tienen que ser asíncronos y con timeout, porque el cliente puede tardar o no responder.
- La disciplina de "el core no imprime" deja de ser una preferencia y pasa a ser un requisito funcional.
