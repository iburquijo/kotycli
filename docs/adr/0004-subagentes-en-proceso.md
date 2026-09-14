# ADR 0004. Subagentes como sesiones en el mismo proceso

**Estado:** aceptado

## Contexto

Necesitamos aislar contexto (explorar un repo sin llenar el historial principal) y paralelizar búsquedas independientes.

## Decisión

- Un subagente es una `Session` nueva que corre el mismo `AgentLoop`, dentro del mismo proceso, como coroutine hija del turn del padre.
- Recibe un subconjunto de tools, hereda la política de permisos y devuelve al padre solo su último texto.
- Profundidad máxima 1 por defecto: un subagente no lanza subagentes.
- Concurrencia acotada por semáforo (4 por defecto).
- Definiciones en markdown con frontmatter, en `.kotycli/agents/` y `~/.kotycli/agents/`, más dos builtin: `explore` (solo lectura) y `general`.

## Alternativas descartadas

- **Subagentes en procesos separados**: más aislamiento, pero hay que serializar todo y perdemos la cancelación estructurada. No hay nada que proteger que lo justifique.
- **Roles fijos (planner, coder, reviewer)**: sin evidencia de que mejore resultados. Los subagentes son un mecanismo de contexto, no una organización.
- **Comunicación bidireccional padre-hijo**: complejidad alta, valor no demostrado. Fuera del MVP.

## Consecuencias

- Los eventos del hijo llegan al mismo flujo que los del padre, etiquetados con `sessionId`. La UI decide cómo agruparlos.
- Cancelar al padre cancela a los hijos gratis.
