# kotycli

Agente de código de terminal en Kotlin/JVM. La arquitectura está en `docs/architecture/` y las decisiones cerradas en `docs/adr/`. Si un cambio contradice un ADR, primero se escribe otro ADR.

## Build y tests

- `./gradlew build` compila, pasa los tests y genera el fat jar en `build/libs/kotycli.jar`.
- `./gradlew test` solo tests. `./gradlew shadowJar` solo el jar.
- Ejecutar: `java -jar build/libs/kotycli.jar` (o `bin/kotycli`, `bin\kotycli.cmd`). `kotycli doctor` diagnostica red y proveedor.
- JDK 21. El wrapper fija Gradle 9.

## Reglas del código

- Un solo módulo, paquetes en `src/main/kotlin/dev/kotycli/` con el layout del doc `07-build-y-distribucion.md`.
- El core (`core/`) no imprime ni conoce proveedores ni frontends. `ArchitectureTest` lo vigila: `println` solo en `frontend/` y `Main.kt`; los tipos de `providers/openai` solo dentro de `providers/`.
- Historial append-only. Las tools devuelven `isError = true`, nunca lanzan fuera de `execute`.
- Permisos, logging, guardas de path y truncado son interceptores, no lógica en las tools.
- Nada con nombre propio para lo que ve el modelo: `AGENTS.md`, skills en `.agents/skills/`, ACP. Solo `.kotycli/` es nuestro.
- Comentarios y mensajes de usuario en castellano; identificadores en inglés.
- Ojo con `/*` dentro de comentarios: Kotlin anida comentarios de bloque.
