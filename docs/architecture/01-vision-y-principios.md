# 01. Visión y principios

## Problema

Los CLIs de agentes actuales (Copilot CLI, Devin CLI, Claude Code, etc.) son código propietario, atan el harness a un proveedor concreto y muchos dependen del sistema operativo. Cambiar de proveedor implica cambiar de herramienta, y con ella de tools, de skills y de forma de trabajar.

## Objetivo

Un harness propio donde el loop, las tools, los skills y los subagentes son nuestros y el modelo es un detalle de configuración.

- Un solo artefacto: `java -jar kotycli.jar`. Funciona donde haya una JVM.
- Proveedor intercambiable por configuración, sin tocar el loop.
- Tools nativas suficientes para trabajar sobre un repo: `bash`, `read`, `write`, `edit`, `glob`, `grep`.
- Skills en formato de carpeta con `SKILL.md`, compatible con el formato abierto de Agent Skills para poder reutilizar los que ya existen.
- Subagentes para aislar contexto y paralelizar exploración.

## No-objetivos (por ahora)

- No es una librería ni un SDK. Es un ejecutable.
- No hay GUI ni servidor web. Terminal.
- No hay sandbox fuerte de ejecución (contenedores, seccomp). El aislamiento es por política de permisos, no por kernel.
- No implementamos MCP en el MVP. Está en el roadmap porque es la forma estándar de añadir tools sin acoplarse a nadie, pero primero el loop.
- No hay multi-usuario ni estado remoto.

## Principios

1. **El core no sabe de proveedores.** El loop trabaja con un modelo de mensajes propio. Cada proveedor es un adaptador detrás de una interfaz. Si un tipo de `com.anthropic.*` o de cualquier otro SDK aparece fuera de su paquete de adaptador, es un bug.
2. **Historial append-only.** Nunca editamos un mensaje ya enviado. Compactar es añadir un resumen y descartar prefijo, nunca reescribir. Algunos proveedores lo exigen (bloques de thinking vinculados al historial) y para todos mejora la caché de prefijo.
3. **Tools tipadas cuando hace falta gatear, auditar o paralelizar.** `bash` da amplitud. `edit`, `write`, `read`, `glob`, `grep` existen porque el harness necesita saber qué se está haciendo para pedir permiso, comprobar que un fichero no cambió desde que se leyó, o ejecutar lecturas en paralelo.
4. **Permisos en el harness, no en el prompt.** El modelo propone, la política decide. Decirle "no borres nada" en el system prompt no es un control de seguridad.
5. **Todo cancelable.** Coroutines de arriba abajo. Ctrl+C mata la llamada al modelo en curso, los procesos hijos y los subagentes.
6. **Presupuestos explícitos.** Máximo de iteraciones por turn, de tokens por sesión, de tiempo por comando y de profundidad de subagentes. Un agente sin límites es un bucle infinito con factura.
7. **Configuración en ficheros, no en código.** Proveedor, modelo, permisos, rutas de skills y agentes se leen de `~/.kotycli/` y de `.kotycli/` en el proyecto.
8. **Un módulo hasta que duela.** Empezamos con un solo módulo Gradle y paquetes bien separados. Se parte en módulos cuando haya una razón, no antes.
