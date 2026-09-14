# 01. Visión y principios

## Problema

Cada herramienta ajena falla por una capa distinta que no se puede tocar: una TUI de pantalla completa que se arrastra en el pipeline de consola de Windows, un backend Node que no traga con la inspección TLS del proxy corporativo, un agente capado por política de empresa, un runtime Python que no corre en Windows nativo. El patrón es siempre el mismo: **la capa rota es de otro**.

Además, cada una ata el harness a un proveedor concreto. Cambiar de proveedor implica cambiar de herramienta, y con ella de tools, de skills y de forma de trabajar.

## Objetivo

Un agente de código de terminal, propio y mínimo: seis tools, un loop, subagentes y varios frontends sobre el mismo core. Construido en la JVM para que Windows corporativo, el proxy y el truststore dejen de ser el problema de otro y pasen a ser un problema resuelto una sola vez, en código propio.

El alcance real de un agente de código, quitado el marketing, son unas 2.000 a 3.000 líneas: un loop de conversación, seis tools, un cliente HTTP y un prompt. Es un tamaño poseíble. Cuando algo se rompa, se arregla en una tarde.

- Un solo artefacto: `java -jar kotycli.jar`. Funciona donde haya una JVM, Windows incluido.
- Proveedor intercambiable por configuración, sin tocar el loop. Copilot en el trabajo, Ollama en casa, cualquier endpoint OpenAI-compatible como plan B permanente.
- Tools nativas suficientes para trabajar sobre un repo: `bash`, `read`, `edit`, `create`, `fetch`, `task`.
- Frontends: TUI append-only, `--plain` para terminales tontos y comint, y ACP por stdio para Emacs, Zed y Neovim sin escribir un frontend por editor.
- Skills en formato abierto (carpeta con `SKILL.md`) cargados bajo demanda.
- Subagentes para aislar contexto y paralelizar exploración.

## Criterio transversal: poseer la capa que falla

Todo lo que no falle en manos de otros (el editor, el terminal, el modelo, el cliente ACP de Emacs) se delega sin complejo. Todo lo que ha fallado (el render en Windows, el HTTP a través del proxy, el loop capado) se escribe en casa.

## No-objetivos (por ahora)

- No es una librería ni un SDK. Es un ejecutable.
- No hay GUI ni servidor web.
- No hay sandbox fuerte de ejecución (contenedores, seccomp). El aislamiento es por interceptores de permisos, no por kernel.
- No hay sistema de hooks configurable. Los interceptores del dispatcher cubren permisos, logging y guardas; para un usuario, el código es la config.
- No implementamos MCP en las primeras versiones. Está en la lista porque es la forma estándar de añadir tools sin acoplarse a nadie, pero primero el loop.
- No hay multi-usuario ni estado remoto.

## Principios

1. **El core no sabe de proveedores ni de frontends.** Hacia abajo, la interfaz `Provider`. Hacia arriba, un `Flow<AgentEvent>`. Si un tipo de SDK o una llamada a `println` aparece en `core/`, es un bug.
2. **Historial append-only.** Nunca editamos un mensaje ya enviado. Compactar es añadir un resumen y descartar prefijo, nunca reescribir.
3. **TUI append-only, nunca pantalla completa.** El transcript fluye hacia arriba como un log y solo el prompt está vivo. En Windows es la diferencia entre escribir unos KB por segundo y repintar miles de celdas por frame.
4. **Tools tipadas cuando hace falta gatear, auditar o comprobar invariantes.** `bash` da amplitud. `edit`, `create`, `read`, `fetch` existen porque el harness necesita saber qué se está haciendo.
5. **Permisos en el harness, no en el prompt.** El modelo propone, un interceptor decide. Decirle "no borres nada" en el system prompt no es un control.
6. **Todo cancelable.** Coroutines de arriba abajo. Ctrl+C mata la llamada al modelo en curso, los procesos hijos y los subagentes.
7. **Presupuestos explícitos.** Máximo de iteraciones por turn, de tokens por sesión, de tiempo por comando y de profundidad de subagentes.
8. **Un `HttpClient` para todo.** Truststore corporativo y proxy resueltos una vez, en un sitio. Proveedores y `fetch` lo heredan.
9. **Configuración en ficheros, no en código.** Proveedor, modelo, permisos y rutas de skills se leen de `~/.agents/` y de `.agents/` en el proyecto.
10. **Un módulo hasta que duela.**
