# 05. Skills

## Qué es un skill

Una carpeta con un `SKILL.md` y, opcionalmente, ficheros de apoyo (scripts, referencias, plantillas). Es instrucciones, no código: le dice al modelo cómo hacer una tarea concreta en este equipo o proyecto.

Adoptamos el formato abierto de Agent Skills tal cual, para reutilizar skills que ya existen y compartir los nuestros:

```
.kotycli/skills/
  deploy-staging/
    SKILL.md
    scripts/check-health.sh
  commit-style/
    SKILL.md
```

```markdown
---
name: deploy-staging
description: Despliega la rama actual a staging y verifica salud. Usar cuando el usuario pida desplegar, subir a staging o probar en staging.
---

# Deploy a staging

1. Ejecuta `./gradlew build` y para si falla.
2. ...
```

Frontmatter mínimo: `name` y `description`. Todo lo demás es opcional.

## Dónde se buscan

En este orden, y el más específico gana si hay colisión de nombre:

1. `.kotycli/skills/` en el proyecto (raíz del repo y, si estamos en un subdirectorio, los padres hasta la raíz del repo).
2. `~/.kotycli/skills/` del usuario.
3. Skills builtin embebidos en el jar (pocos).

## Carga progresiva sin tool nueva

Un skill tiene dos niveles:

1. **Descripción, siempre en contexto.** El system prompt incluye una lista `nombre — descripción — ruta absoluta de SKILL.md` de todos los skills disponibles. Cuesta unas decenas de tokens por skill.
2. **Cuerpo, bajo demanda.** Cuando el modelo decide que un skill aplica, **lo lee con `read`** usando la ruta que ya tiene. No hay tool `skill`: seguimos en seis tools y el modelo ya sabe leer ficheros. Los ficheros de apoyo se leen con `read` o se ejecutan con `bash` desde la misma carpeta.

El usuario también puede invocar un skill explícitamente con `/nombre argumentos` en la TUI (o el equivalente en ACP). Eso equivale a un mensaje de usuario que contiene el cuerpo del skill más los argumentos.

## Lo que un skill NO es

- No define tools nuevas. Para eso está el registry (y, más adelante, MCP).
- No cambia permisos. Un skill puede decir "ejecuta `rm -rf build`" y el interceptor sigue pidiendo confirmación.
- No se carga entero en el system prompt. Para instrucciones siempre presentes está `KOTYCLI.md`.

## Instrucciones de proyecto: `KOTYCLI.md`

Un fichero `KOTYCLI.md` en la raíz del proyecto (y `~/.kotycli/KOTYCLI.md` para el usuario) se inyecta siempre al system prompt. Es el sitio para convenciones del repo, comandos de build y el shell que hay debajo de `bash`. Se mantiene corto: todo lo que se pueda mover a un skill, se mueve.

## Caché

`SkillLoader` escanea los directorios al arrancar y guarda `nombre -> (descripción, ruta)`. El cuerpo se lee del disco en cada uso, así que editar un `SKILL.md` a mitad de sesión tiene efecto sin reiniciar. Añadir un skill nuevo requiere `/reload`.
