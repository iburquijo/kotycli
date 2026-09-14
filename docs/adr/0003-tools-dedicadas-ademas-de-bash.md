# ADR 0003. Tools dedicadas además de bash

**Estado:** aceptado

## Contexto

Con solo `bash` el modelo puede hacer todo, pero el harness solo ve una cadena opaca: no puede pedir permiso de forma específica, no puede paralelizar lecturas, y no puede evitar que se sobreescriba un fichero que cambió desde que se leyó.

## Decisión

MVP con `bash`, `read`, `write`, `edit`. Fase 2 añade `glob` y `grep`.

Criterio para promover algo a tool dedicada: el harness necesita **gatear**, **auditar**, **paralelizar** o **comprobar invariantes** sobre esa acción. Si no cumple ninguno, va por `bash`.

Invariantes que solo son posibles con tools dedicadas:

- `edit` y `write` exigen que el fichero se haya leído en la sesión y que su `mtime` no haya cambiado.
- `read`, `glob`, `grep` son `readOnly` y se ejecutan en paralelo cuando el modelo pide varias a la vez.
- `edit` exige que `old_string` sea único en el fichero.

## Alternativas descartadas

- **Solo bash**: descartado por lo anterior.
- **Usar las tools `bash`/`text_editor` definidas por Anthropic**: tienen schema fijo y son específicas de un proveedor. Nuestras tools tienen schema propio y se declaran igual en todos los proveedores.
- **Muchas tools desde el principio (web, notebook, etc.)**: cada tool cuesta tokens en cada llamada. Se añaden cuando se echen de menos.

## Consecuencias

- `grep` delega en `rg` si está instalado. Si no, implementación propia más lenta. El formato de salida es el mismo.
- Hay que escribir y mantener descripciones de tool buenas: son parte del prompt.
