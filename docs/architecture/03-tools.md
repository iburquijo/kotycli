# 03. Tools

Seis tools. Ni una más hasta que alguien la eche de menos con un caso concreto.

## Contrato

```kotlin
interface Tool {
    val name: String
    val description: String            // lo que ve el modelo; aquí se juega la mitad de la calidad
    val inputSchema: JsonObject        // generado desde la data class @Serializable del input
    val readOnly: Boolean              // true => paralelizable y normalmente sin pedir permiso
    val timeout: Duration
    suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult
}

class ToolContext(
    val agent: AgentContext,
    val workDir: Path,                 // canónico; todo path del input se resuelve contra él
    val fileTracker: FileTracker,      // ficheros leídos en esta sesión y su mtime
    val http: HttpClient,              // el único, con truststore y proxy ya resueltos
)

class ToolRegistry(tools: List<Tool>) {
    operator fun get(name: String): Tool?
    fun definitions(): List<ToolDefinition>              // lo que se manda al proveedor
    fun restrictedTo(names: Set<String>): ToolRegistry   // toolsets por rol de subagente
}
```

### Schema desde `@Serializable`

Cada tool declara su input como `data class` anotada. El JSON Schema se genera del `SerialDescriptor` de `kotlinx.serialization`, con una anotación propia `@Description("...")` por campo para el texto que ve el modelo. Un solo sitio de verdad: cambiar un campo cambia el schema, la deserialización y la descripción.

```kotlin
@Serializable
data class EditInput(
    @Description("Ruta relativa al working dir") val path: String,
    @Description("Texto exacto a sustituir. Tiene que aparecer una sola vez") val old_string: String,
    @Description("Texto nuevo") val new_string: String,
)
```

### Reglas comunes

- **Paths canonicalizados** contra el working dir por el interceptor `PathGuard` antes de llegar a la tool. Una tool nunca ve `../../etc`.
- **Truncado centralizado** en el interceptor `Truncate` (~30-50 KB, cabeza y cola, aviso en el texto). Las tools no truncan por su cuenta.
- **Errores como `isError = true`**, nunca excepciones fuera de `execute`. Un error nunca tumba el loop.
- **Descripciones honestas con Windows**: la description de `bash` dice explícitamente qué shell hay debajo.

## Las seis tools

| Tool | Contrato | Detalle Windows / decisión clave |
|------|----------|----------------------------------|
| `bash` | Ejecuta un comando con timeout (~2 min), stdout y stderr mezclados en orden, exit code al final. | Shell explícito en la description (Git Bash o PowerShell, detectado al arrancar). Al cancelar, mata el árbol entero vía `ProcessHandle.descendants()`. `cwd` persiste entre llamadas; el resto del estado del shell no. |
| `read` | Numera líneas (`cat -n`), `offset`/`limit`, truncado duro avisado. Registra path y `mtime` en `FileTracker`. | Detecta binarios y se niega con mensaje claro. `readOnly = true`. |
| `edit` | `str_replace` con match único: 0 ó 2+ apariciones = error explicado con el conteo. `replace_all` opcional. | Exige lectura previa en la sesión y `mtime` sin cambios ("el fichero cambió desde que lo leíste"). Normaliza CRLF antes de comparar; preserva el line ending original al escribir. |
| `create` | Crea un fichero nuevo con el contenido dado. Crea directorios intermedios. | **Falla si existe.** Sobreescribir exige pasar por `edit`, o borrar con `bash` primero. Evita que el modelo machaque algo que no ha visto. |
| `fetch` | HTTP GET, HTML a markdown (jsoup), límite de tamaño, timeout 30 s. | Aquí vive el trabajo de truststore y proxy, heredado del `HttpClient` común. `readOnly = true`. Solo `http(s)`; nada de `file://`. |
| `task` | Lanza un subagente (`prompt`, `agent_type`). Devuelve solo su mensaje final. | Toolset por rol: `explorer` = solo lectura; `implementor` = todo menos `task`. Ver [04-subagentes](04-subagentes.md). |

### Por qué `create` y no `write`

Un `write` que sobreescribe es la forma más fácil de perder trabajo: el modelo lee una versión, razona, y escribe encima de otra. `create` que falla si existe más `edit` con comprobación de `mtime` cubren los mismos casos con una invariante clara: **nada se sobreescribe sin haberse leído**. Reescribir un fichero entero es raro y se puede hacer con `edit` (old_string = contenido completo) o con `bash rm` + `create`, ambos visibles y gateables.

### Por qué no hay `glob` ni `grep`

`rg` y `fd` por `bash` hacen lo mismo y el modelo ya sabe usarlos. Lo que se pierde es paralelismo automático (bash siempre es serie) y permisos granulares. Si en la práctica la fricción es real, son candidatas a promoción: el criterio del ADR 0003 (gatear, auditar, paralelizar, invariantes) sigue valiendo. Hasta entonces, seis.

## Permisos

Viven en el interceptor `Permissions`, no en las tools ni en el prompt.

```kotlin
sealed interface Decision {
    object Allow : Decision
    object Ask : Decision
    data class Deny(val reason: String) : Decision
}

interface PermissionPolicy {
    fun decide(ctx: AgentContext, tool: Tool, input: JsonObject): Decision
}
```

Modos:

| Modo | `read`/`fetch` | `edit`/`create` | `bash` | `task` |
|------|----------------|-----------------|--------|--------|
| `default` | Allow | Ask | Ask (Allow si matchea allowlist) | Allow |
| `accept-edits` | Allow | Allow | Ask (Allow si matchea allowlist) | Allow |
| `yolo` | Allow | Allow | Allow | Allow |

Además:

- **Reglas** por tool y patrón: `bash(git status*)` allow, `bash(rm -rf*)` deny, `bash(git push --force*)` ask, `create(/etc/**)` deny. Deny gana a allow, allow gana a ask.
- **Guarda de paths**: las tools de fichero solo operan bajo el working dir salvo regla explícita (`--allow-path`).
- **"Permitir siempre en esta sesión"** como respuesta al `Ask`, y opcionalmente persistir la regla en `.kotycli/settings.json`.
- Los subagentes heredan la política del padre y nunca pueden ser más permisivos.
- Sin frontend que responda (`--plain` sin TTY, timeout), `Ask` se resuelve como `Deny`.

El `Ask` se materializa como `AgentEvent.PermissionAsk`. En la TUI es la línea `[s] permitir · [S] siempre en esta sesión · [n] denegar`; en ACP es `session/request_permission`; en Emacs lo pinta agent-shell.
