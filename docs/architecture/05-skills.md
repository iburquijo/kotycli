# 05. Skills

## Qué es un skill

Una carpeta con un `SKILL.md` y, opcionalmente, ficheros de apoyo (scripts, referencias, plantillas). Es instrucciones, no código: le dice al modelo cómo hacer una tarea concreta en este equipo o proyecto.

Adoptamos el formato abierto de Agent Skills tal cual, para poder reutilizar skills que ya existen y compartir los nuestros:

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

1. `.kotycli/skills/` en el proyecto (raíz del repo y, si estamos en un subdirectorio, también los padres hasta la raíz del repo).
2. `~/.kotycli/skills/` del usuario.
3. Skills builtin embebidos en el jar (pocos: `init`, `review`).

## Carga progresiva

Esto es lo importante. Un skill tiene dos niveles:

1. **Descripción, siempre en contexto.** Al construir el system prompt se añade una lista `nombre: descripción` de todos los skills disponibles. Cuesta unas decenas de tokens por skill.
2. **Cuerpo, bajo demanda.** Cuando el modelo decide que un skill aplica, llama a la tool `skill` con el nombre. El harness devuelve el cuerpo completo del `SKILL.md` como `ToolResult`, con la ruta absoluta de la carpeta para que el modelo pueda leer ficheros de apoyo con `read` o ejecutar scripts con `bash`.

```kotlin
class SkillTool(private val loader: SkillLoader) : Tool {
    override val name = "skill"
    override val readOnly = true
    // input: { "name": "deploy-staging" }
    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val skill = loader.find(input.name) ?: return error("Skill desconocido. Disponibles: ${loader.names()}")
        return ok("Skill '${skill.name}' cargado desde ${skill.dir}\n\n${skill.body}")
    }
}
```

El usuario también puede invocar un skill explícitamente con `/nombre argumentos` en la TUI. Eso equivale a un mensaje de usuario que contiene el cuerpo del skill más los argumentos.

## Lo que un skill NO es

- No define tools nuevas. Para eso está el registry (y, más adelante, MCP).
- No cambia permisos. Un skill puede decir "ejecuta `rm -rf build`" y la política sigue pidiendo confirmación.
- No se carga entero en el system prompt. Si alguien necesita que unas instrucciones estén siempre presentes, eso es `KOTYCLI.md` (ver abajo), no un skill.

## Instrucciones de proyecto: `KOTYCLI.md`

Aparte de los skills, un fichero `KOTYCLI.md` en la raíz del proyecto (y `~/.kotycli/KOTYCLI.md` para el usuario) se inyecta siempre al system prompt. Es el sitio para convenciones del repo, comandos de build y cosas que el modelo tiene que saber en cada turn. Se mantiene corto: todo lo que se pueda mover a un skill, se mueve.

## Caché de skills

`SkillLoader` escanea los directorios al arrancar la sesión y guarda `nombre -> (descripción, ruta)`. El cuerpo se lee del disco en cada invocación de `skill`, así editar un `SKILL.md` a mitad de sesión tiene efecto sin reiniciar. Añadir un skill nuevo sí requiere reescanear (`/reload-skills`).
