package com.softbenur.kotycli.frontend

import com.softbenur.kotycli.skills.SkillCatalog

/** Lo que el usuario ha escrito, ya interpretado. Cada frontend decide qué sabe hacer con cada caso. */
sealed interface Command {
    data object Exit : Command
    data object Help : Command
    data object Config : Command
    data object Copy : Command
    data object Reload : Command

    /** `/compact` con instrucciones opcionales: «céntrate en el bug de los permisos». */
    data class Compact(val instructions: String?) : Command

    /** `/edit [texto]`: abre el `$EDITOR` con ese texto dentro y manda lo que quede al guardar. */
    data class Edit(val initial: String) : Command

    /** `/nombre args` que ha resultado ser un skill: ya expandido a su cuerpo. */
    data class Skill(val name: String, val expanded: String) : Command

    /** Un `/loquesea` que no es ni builtin ni skill. Se avisa en vez de mandárselo al modelo. */
    data class Unknown(val name: String) : Command

    data class Prompt(val text: String) : Command
}

/**
 * Los comandos `/` que comparten la TUI y `--plain` (doc 08). Los builtin ganan a un skill que se llame
 * igual: si no, instalar un skill llamado `exit` dejaría al usuario sin forma de salir.
 */
object Commands {
    val builtins = listOf("/exit", "/help", "/compact", "/config", "/copy", "/edit", "/reload")

    fun parse(line: String, skills: SkillCatalog): Command {
        val text = line.trim()
        if (!text.startsWith("/")) return Command.Prompt(text)
        val name = text.drop(1).substringBefore(' ')
        val args = text.drop(1).substringAfter(' ', "").trim()
        return when (name) {
            "exit", "quit" -> Command.Exit
            "help" -> Command.Help
            "compact" -> Command.Compact(args.ifBlank { null })
            "config" -> Command.Config
            "copy" -> Command.Copy
            "edit" -> Command.Edit(args)
            "reload" -> Command.Reload
            else -> skills.expand(text)?.let { Command.Skill(name, it) } ?: Command.Unknown(name)
        }
    }

    /** El texto de `/help`: los builtin y, detrás, los skills con la primera línea de su descripción. */
    fun help(skills: SkillCatalog): String {
        val rows = listOf(
            "/help" to "esta ayuda",
            "/compact [foco]" to "resume la conversación y libera contexto",
            "/config" to "la configuración efectiva y de qué ficheros sale",
            "/copy" to "copia la última respuesta al portapapeles",
            "/edit [texto]" to "escribe el mensaje en \$EDITOR",
            "/reload" to "relee config, AGENTS.md, skills y roles",
            "/exit" to "salir",
        ) + skills.all.map { "/${it.name}" to it.description.lineSequence().first().take(90) }
        val width = rows.maxOf { it.first.length } + 2
        return rows.joinToString("\n") { (name, help) -> name.padEnd(width) + help }
    }
}
