# ADR 0003. Seis tools: bash, read, edit, create, fetch, task

**Estado:** aceptado

## Contexto

Con solo `bash` el modelo puede hacer todo, pero el harness solo ve una cadena opaca: no puede pedir permiso de forma específica, no puede evitar que se sobreescriba un fichero que cambió desde que se leyó, y no puede paralelizar. Con demasiadas tools, cada llamada al modelo paga sus schemas y el harness crece sin control.

## Decisión

Seis tools y ninguna más hasta que haya un caso concreto:

| Tool | Por qué existe aparte de bash |
|------|-------------------------------|
| `bash` | Amplitud. Todo lo que no tenga tool dedicada. Shell explícito en la description |
| `read` | Registra `mtime` para que `edit` compruebe staleness; numera líneas; detecta binarios; paralelizable |
| `edit` | `str_replace` con match único, lectura previa obligatoria, `mtime` sin cambios, CRLF normalizado |
| `create` | Falla si el fichero existe. Nada se sobreescribe sin haberse leído |
| `fetch` | HTTP a través del `HttpClient` común con truststore y proxy; HTML a markdown; paralelizable |
| `task` | Subagente con toolset por rol (ADR 0004) |

Criterio para promover algo a tool dedicada: el harness necesita **gatear**, **auditar**, **paralelizar** o **comprobar invariantes** sobre esa acción. Si no cumple ninguno, va por `bash`.

Schema JSON generado desde la `data class` `@Serializable` del input, con `@Description` por campo. Un solo sitio de verdad.

## Alternativas descartadas

- **Solo bash**: descartado por lo anterior.
- **`write` que sobreescribe en vez de `create`**: es la forma más fácil de perder trabajo. Reescribir un fichero entero es raro y se hace con `edit` o con `bash rm` + `create`, ambos visibles y gateables.
- **`glob` y `grep` dedicadas**: `rg` y `fd` por `bash` hacen lo mismo y el modelo ya sabe usarlos. Se pierde paralelismo automático y permisos granulares. Candidatas a promoción si la fricción es real; no antes.
- **Usar las tools `bash`/`text_editor` definidas por un proveedor**: schema fijo y específicas de ese proveedor. Las nuestras se declaran igual en todos.
- **Muchas tools desde el principio (web search, notebooks, etc.)**: cada tool cuesta tokens en cada llamada. Se añaden cuando se echen de menos.

## Consecuencias

- Hay que escribir y mantener descripciones de tool buenas: son parte del prompt.
- Truncado, guarda de paths y logging no viven en las tools sino en interceptores (ADR 0009), así que cada tool es pequeña.
