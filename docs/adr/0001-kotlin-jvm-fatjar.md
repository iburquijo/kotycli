# ADR 0001. Kotlin sobre JVM frente a Rust, distribuido como fat jar

**Estado:** aceptado

## Contexto

Los CLIs de agentes existentes dependen del sistema operativo o de un runtime concreto (Node, Python, binarios nativos por plataforma). Queremos un solo artefacto que corra en cualquier máquina con una JVM, incluidas las corporativas con Windows, proxy inspector y política de instalación restrictiva.

## Decisión

- Lenguaje: Kotlin, JDK 21 como target.
- Build: Gradle Kotlin DSL con el plugin Shadow. El entregable es `kotycli.jar` ejecutable con `java -jar`.
- Un solo módulo Gradle hasta que haya una razón concreta para partirlo.
- Concurrencia con `kotlinx.coroutines`: la cancelación estructurada es el motivo.

## Alternativas descartadas

- **Rust**: el 95% del tiempo el proceso espera a la API o a procesos externos; el rendimiento no discrimina. Discrimina el manejo maduro de proxies y truststores corporativos (incluido el almacén de Windows), las coroutines para cancelación estructurada, y que es el stack de casa.
- **GraalVM native-image**: arranque más rápido, pero build por plataforma y fricción con reflexión. Se puede añadir después sin cambiar el código si evitamos reflexión (por eso `kotlinx.serialization` y no Jackson).
- **Kotlin Multiplatform / Native**: menos ecosistema, más riesgo, y perdemos el `HttpClient` del JDK y el acceso al truststore de Windows.
- **Multi-módulo desde el día 1**: sobrecarga sin beneficio con un solo desarrollador.

## Consecuencias

- Arranque de JVM de ~0.5 s. Aceptable para una sesión interactiva; molesto para `--plain` en scripts. Se mitiga con AppCDS si hace falta.
- Cualquier dependencia que use reflexión pesada complica un futuro native-image. Se evita.
