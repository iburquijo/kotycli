# ADR 0001. Kotlin sobre JVM, distribuido como fat jar

**Estado:** aceptado

## Contexto

Los CLIs de agentes existentes dependen del sistema operativo o de un runtime concreto (Node, binarios nativos por plataforma). Queremos un solo artefacto que corra en cualquier máquina con una JVM, incluidas las corporativas donde instalar cosas es un dolor.

## Decisión

- Lenguaje: Kotlin, JDK 21 como target.
- Build: Gradle Kotlin DSL con el plugin Shadow. El entregable es `kotycli.jar` ejecutable con `java -jar`.
- Un solo módulo Gradle hasta que haya una razón concreta para partirlo.
- Concurrencia con `kotlinx.coroutines`, no con hilos a mano ni con virtual threads (la cancelación estructurada es el motivo).

## Alternativas descartadas

- **GraalVM native-image**: arranque más rápido, pero build por plataforma y fricción con reflexión. Se puede añadir después sin cambiar el código si evitamos reflexión (por eso `kotlinx.serialization` y no Jackson).
- **Kotlin Multiplatform / Native**: menos ecosistema, más riesgo, y perdemos los SDKs Java.
- **Multi-módulo desde el día 1**: sobrecarga sin beneficio con un solo desarrollador.

## Consecuencias

- Arranque de JVM de ~0.5 s. Aceptable para una sesión interactiva; molesto para `--print` en scripts. Se mitiga con AppCDS si hace falta.
- Cualquier dependencia que use reflexión pesada complica un futuro native-image. Se evita.
