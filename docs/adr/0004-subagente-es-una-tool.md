# ADR 0004. Subagente = una tool más, mismo runLoop, profundidad 2

**Estado:** aceptado

## Contexto

Necesitamos aislar contexto (explorar un repo sin llenar el historial principal) y paralelizar búsquedas independientes.

## Decisión

- La tool `task` lanza otro `runLoop` con un `AgentContext` virgen, en el mismo proceso, como coroutine hija del turn del padre. Un solo loop reutilizado recursivamente.
- Contrato: contexto virgen, solo el mensaje final vuelve al padre, presupuesto de tokens descontado del padre (mismo objeto `Budget`).
- Toolset por rol: `explorer` = solo lectura; `implementor` = todo menos `task`. Roles propios en markdown con frontmatter.
- Profundidad máxima 2. Concurrencia acotada por semáforo (4 por defecto).
- Hereda la política de permisos del padre y nunca la relaja.

## Alternativas descartadas

- **Subagentes en procesos separados**: más aislamiento, pero hay que serializar todo y perdemos la cancelación estructurada. No hay nada que proteger que lo justifique.
- **Roles fijos (planner, coder, reviewer)**: sin evidencia de que mejore resultados. Los subagentes son un mecanismo de contexto, no una organización.
- **Comunicación bidireccional padre-hijo**: complejidad alta, valor no demostrado.
- **Profundidad ilimitada**: un agente que delega en un agente que delega es un bucle con factura.

## Consecuencias

- Los eventos del hijo llegan al mismo flujo que los del padre, etiquetados con su id. El frontend decide cómo agruparlos.
- Cancelar al padre cancela a los hijos gratis.
- Un subagente que necesita 30 rondas es una señal de que la tarea estaba mal partida, no de que hay que subir el límite.
