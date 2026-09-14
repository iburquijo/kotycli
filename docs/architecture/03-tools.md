# 03. Tools

## Contrato

```kotlin
interface Tool {
    val name: String
    val description: String            // lo que ve el modelo; aquí se juega la mitad de la calidad
    val inputSchema: JsonObject        // JSON Schema del input
    val readOnly: Boolean              // true => paralelizable y normalmente sin pedir permiso
    val timeout: Duration
    suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult
}

class ToolContext(
    val session: Session,
    val cwd: Path,
    val fileTracker: FileTracker,      // ficheros leídos en esta sesión y su mtime
)

class ToolRegistry(tools: List<Tool>) {
    operator fun get(name: String): Tool?
    fun definitions(): List<ToolDefinition>       // lo que se manda al proveedor
    fun restrictedTo(names: Set<String>): ToolRegistry   // para subagentes
}
```

Cada tool vive en su propio fichero bajo `tools/`. El input se deserializa con `kotlinx.serialization` a una `data class` propia de la tool; el `inputSchema` se genera a mano por ahora (es un JSON pequeño y estable, y así controlamos las descripciones de cada campo).

## Tools del MVP

### `bash`

Ejecuta un comando en un shell. Es la tool de amplitud: todo lo que no tenga tool dedicada pasa por aquí.

- Input: `command: String`, `timeout_ms: Int?` (máx. 10 min), `description: String?` (lo que la UI enseña al usuario).
- Ejecución con `ProcessBuilder`, `sh -c` en POSIX y `cmd /c` o PowerShell en Windows. El shell se detecta al arrancar.
- `cwd` persiste entre llamadas dentro de la sesión. El resto del estado del shell no (cada llamada es un proceso nuevo).
- Salida: stdout y stderr mezclados en orden, exit code al final. Truncado a 30k caracteres conservando cabeza y cola.
- Al cancelar, se destruye el proceso y todos sus descendientes.
- `readOnly = false` siempre. No intentamos adivinar si un comando es de lectura: el modelo tiene `read`, `glob` y `grep` para eso, y la política de permisos puede tener un allowlist de prefijos (`git status`, `ls`, `cat`).

### `read`

Lee un fichero. Devuelve el contenido con números de línea (`cat -n`) para que `edit` sea preciso.

- Input: `path`, `offset: Int?`, `limit: Int?` (por defecto 2000 líneas).
- Registra el fichero y su `mtime` en `FileTracker`. Esto es lo que habilita la comprobación de `edit`.
- Ficheros binarios: rechaza con mensaje claro. Imágenes: fuera del MVP.
- `readOnly = true`.

### `write`

Crea o sobreescribe un fichero completo.

- Input: `path`, `content`.
- Si el fichero existe y no ha sido leído en esta sesión, falla: "Lee el fichero antes de sobreescribirlo". Evita que el modelo machaque algo que no ha visto.
- Crea directorios intermedios.
- `readOnly = false`, requiere permiso en modo por defecto.

### `edit`

Reemplazo exacto de una cadena dentro de un fichero.

- Input: `path`, `old_string`, `new_string`, `replace_all: Boolean = false`.
- Invariantes:
  1. El fichero tiene que haberse leído en esta sesión (`FileTracker`).
  2. El `mtime` actual tiene que coincidir con el registrado al leerlo. Si no, falla con "el fichero cambió desde que lo leíste, vuelve a leerlo".
  3. `old_string` tiene que aparecer exactamente una vez (salvo `replace_all`). Cero ocurrencias o más de una: error con el conteo.
- Tras escribir, actualiza el `mtime` en `FileTracker`.
- `readOnly = false`, requiere permiso en modo por defecto.

Esta tool es el motivo principal para no dejar que todo pase por `bash`: la comprobación de staleness no se puede hacer con `sed`.

### `glob`

Busca ficheros por patrón (`**/*.kt`). Devuelve rutas ordenadas por fecha de modificación.

- Input: `pattern`, `path: String?`.
- Implementación con `java.nio.file.PathMatcher` + `Files.walk`. Respeta `.gitignore` en una segunda iteración.
- `readOnly = true`.

### `grep`

Búsqueda de contenido por regex.

- Input: `pattern`, `path: String?`, `glob: String?`, `output_mode: files | content | count`, `context: Int?`.
- Si hay `rg` en el PATH, se delega en él (es mucho más rápido y ya respeta `.gitignore`). Si no, implementación propia en Kotlin con `Regex` sobre `Files.walk`. El resultado tiene el mismo formato en ambos casos.
- `readOnly = true`.

## Tools de sistema (no son "de fichero" pero viven en el mismo registry)

- `agent`: lanza un subagente. Ver [04-subagentes](04-subagentes.md).
- `skill`: carga un skill en contexto. Ver [05-skills](05-skills.md).

## Permisos

```kotlin
sealed interface Decision {
    object Allow : Decision
    object Ask : Decision
    data class Deny(val reason: String) : Decision
}

interface PermissionPolicy {
    fun decide(session: Session, tool: Tool, input: JsonObject): Decision
}
```

Modos, de más restrictivo a menos:

| Modo | `read`/`glob`/`grep` | `edit`/`write` | `bash` | `agent` |
|------|----------------------|----------------|--------|---------|
| `default` | Allow | Ask | Ask (Allow si matchea allowlist) | Allow |
| `accept-edits` | Allow | Allow | Ask (Allow si matchea allowlist) | Allow |
| `yolo` | Allow | Allow | Allow | Allow |

Además:

- **Allowlist / denylist** en configuración: reglas `bash(git status*)`, `bash(rm -rf*)` -> deny, `edit(src/**)`, `write(/etc/**)` -> deny. Deny gana a allow, allow gana a ask.
- **Sandbox de rutas**: por defecto `read`/`write`/`edit`/`glob`/`grep` solo operan bajo el `cwd` del proyecto. Salir requiere `--allow-path` o una regla explícita.
- **"Recordar esta decisión"**: cuando el usuario acepta un `Ask`, puede persistirlo como regla en `.kotycli/settings.json`.
- Los subagentes heredan la política del padre y nunca pueden ser más permisivos que él.

El `Ask` se resuelve con un `AgentEvent.PermissionRequested` que la UI responde. En modo `--print` (sin TTY), `Ask` se convierte en `Deny` automáticamente.
