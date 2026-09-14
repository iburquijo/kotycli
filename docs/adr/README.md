# ADRs (Architecture Decision Records)

Una decisión por fichero. Formato: contexto, decisión, alternativas, consecuencias. Una vez el proyecto tenga código, un ADR no se edita: si se cambia de opinión, se escribe otro que lo supersede. Mientras esto es un borrador previo al código, se corrigen en sitio.

| # | Decisión | Estado |
|---|----------|--------|
| [0001](0001-kotlin-jvm-fatjar.md) | Kotlin sobre JVM frente a Rust, distribuido como fat jar | aceptado |
| [0002](0002-core-agnostico-de-proveedor.md) | El core no conoce ningún proveedor ni frontend | aceptado |
| [0003](0003-seis-tools.md) | Seis tools: bash, read, edit, create, fetch, task | aceptado |
| [0004](0004-subagente-es-una-tool.md) | Subagente = una tool más, mismo runLoop, profundidad 2 | aceptado |
| [0005](0005-permisos-en-el-harness.md) | Permisos decididos por el harness, como interceptor | aceptado |
| [0006](0006-historial-append-only.md) | Historial de mensajes append-only | aceptado |
| [0007](0007-formato-abierto-de-skills.md) | Skills en formato Agent Skills, sin tool dedicada | aceptado |
| [0008](0008-tui-append-only.md) | TUI append-only, nunca pantalla completa | aceptado |
| [0009](0009-interceptores-no-hooks.md) | Interceptores sí, sistema de hooks no | aceptado |
| [0010](0010-editores-via-acp.md) | Emacs y otros editores vía ACP, no vía plugin propio | aceptado |
| [0011](0011-httpclient-unico.md) | Un único HttpClient con truststore y proxy corporativos | aceptado |
| [0012](0012-copilot-con-salida-de-emergencia.md) | Copilot como proveedor principal, OpenAI-compatible como plan B permanente | aceptado |
| [0013](0013-estandares-abiertos.md) | Estándares abiertos para todo lo que ve el modelo o comparte el usuario | aceptado |
