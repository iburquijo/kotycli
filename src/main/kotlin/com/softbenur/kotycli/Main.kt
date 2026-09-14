package com.softbenur.kotycli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.softbenur.kotycli.agents.AgentTypeLoader
import com.softbenur.kotycli.config.Config
import com.softbenur.kotycli.config.ConfigFile
import com.softbenur.kotycli.config.Dirs
import com.softbenur.kotycli.core.AgentConfig
import com.softbenur.kotycli.core.AgentContext
import com.softbenur.kotycli.core.Budget
import com.softbenur.kotycli.core.PermissionMode
import com.softbenur.kotycli.core.runLoop
import com.softbenur.kotycli.frontend.Session
import com.softbenur.kotycli.frontend.plain.Plain
import com.softbenur.kotycli.frontend.tui.Tui
import com.softbenur.kotycli.http.Http
import com.softbenur.kotycli.http.HttpConfig
import com.softbenur.kotycli.interceptors.PathGuard
import com.softbenur.kotycli.interceptors.Permissions
import com.softbenur.kotycli.interceptors.RulePolicy
import com.softbenur.kotycli.interceptors.ToolLog
import com.softbenur.kotycli.interceptors.Truncate
import com.softbenur.kotycli.prompt.SystemPrompt
import com.softbenur.kotycli.providers.Providers
import com.softbenur.kotycli.skills.SkillLoader
import com.softbenur.kotycli.tools.BashTool
import com.softbenur.kotycli.tools.CreateTool
import com.softbenur.kotycli.tools.EditTool
import com.softbenur.kotycli.tools.FetchTool
import com.softbenur.kotycli.tools.ReadTool
import com.softbenur.kotycli.tools.Shell
import com.softbenur.kotycli.tools.TaskTool
import com.softbenur.kotycli.tools.ToolEnv
import com.softbenur.kotycli.tools.ToolRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.system.exitProcess

/** Todo lo que hace falta para montar una sesión, compartido por `kotycli` y `kotycli doctor`. */
class CommonOptions : CliktCommand(name = "kotycli") {
    override val invokeWithoutSubcommand = true
    override val printHelpOnEmptyArgs = false

    override fun help(context: Context) = "Agente de código de terminal. Sin prompt abre la sesión interactiva."

    val prompt by argument(help = "Prompt inicial. En --plain ejecuta un solo turn y sale").optional()
    val plain by option("--plain", help = "Sin ANSI ni raw mode: para comint, pipes y CI").flag()
    val provider by option("--provider", help = "Nombre del proveedor en la config (p.ej. local, corp)")
    val model by option("--model", help = "Modelo a usar")
    val mode by option("--mode", help = "Modo de permisos: default | accept-edits | yolo")
    val cwd by option("--cwd", help = "Working dir (por defecto el actual)")
    val truststore by option("--truststore", help = "PEM, JKS o PKCS12 con las CAs corporativas")
    val allowPath by option("--allow-path", help = "Ruta fuera del working dir que las tools pueden tocar").multiple()
    val yes by option("--yes", help = "En --plain, aprueba todas las peticiones de permiso").flag()
    val maxTokens by option("--max-tokens", help = "Presupuesto de tokens de la sesión")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val boot = try {
            bootstrap()
        } catch (e: IllegalArgumentException) {
            echo("kotycli: ${e.message}", err = true)
            exitProcess(2)
        }
        runBlocking {
            val session = boot.session()
            if (plain) {
                val ui = Plain(session, assumeYes = yes)
                if (prompt != null) ui.runOnce(prompt!!) else ui.runInteractive()
            } else {
                val ui = Tui(session, boot.banner())
                if (prompt != null) coroutineScope { session.turn(prompt!!) } // un turn y luego la sesión interactiva
                ui.run()
            }
        }
    }

    fun bootstrap(): Bootstrap {
        val workDir = (cwd?.let { Path.of(it) } ?: Path.of("")).toAbsolutePath().normalize()
        val dirs = Dirs(project = workDir)
        val overrides = ConfigFile(
            provider = provider,
            model = model,
            permissionMode = mode,
            http = truststore?.let { HttpConfig(truststore = it) },
            maxTokensPerSession = maxTokens?.toInt(),
        )
        val config = Config.load(dirs, overrides)
        return Bootstrap(config, workDir, allowPath.map { Path.of(it).toAbsolutePath().normalize() })
    }
}

class Bootstrap(val config: Config, val workDir: Path, val allowedPaths: List<Path>) {
    val shell = Shell.detect()
    val httpAndReport = Http.build(config.http)
    val http get() = httpAndReport.first

    fun banner(): String = "kotycli · ${config.providerName}/${config.model} · ${config.file.permissionMode ?: "default"} · ${shell.displayName} · $workDir"

    fun session(): Session {
        val provider = Providers.build(config.providerName, config.provider, http)
        val env = ToolEnv(workDir = workDir, http = http, shell = shell, allowedPaths = allowedPaths)
        val agentTypes = AgentTypeLoader.load(config.dirs)
        val skills = SkillLoader.load(config.dirs, workDir)
        val contextPrompt = SystemPrompt.context(config.dirs, shell, workDir, skills)
        val tools = ToolRegistry(listOf(
            BashTool(shell, workDir), ReadTool(), EditTool(), CreateTool(), FetchTool(),
            TaskTool(agentTypes, contextPrompt, config.file.subagentModel),
        ))
        val policy = RulePolicy(config.settings.rules())
        val interceptors = listOf(PathGuard(), Permissions(policy), ToolLog(config.dirs.logsDir.resolve("tools.jsonl")), Truncate())
        val agentConfig = AgentConfig(
            systemPrompt = SystemPrompt.build(config.dirs, shell, workDir, skills),
            model = config.model,
            maxOutputTokens = config.provider.maxOutputTokens,
            maxIterationsPerTurn = config.file.maxIterationsPerTurn ?: 50,
            permissionMode = config.file.permissionMode?.let { PermissionMode.parse(it) } ?: PermissionMode.DEFAULT,
        )
        val root = AgentContext(agentConfig, provider, tools, interceptors, Budget(config.file.maxTokensPerSession), env)
        return object : Session {
            override val root = root
            override val skills = skills
            private var current: Job? = null
            override suspend fun turn(prompt: String) = coroutineScope {
                val job = launch { runLoop(root, prompt) }
                current = job
                job.join()
            }
            override fun cancel() { current?.cancel() }
        }
    }
}

class Doctor : CliktCommand(name = "doctor") {
    override fun help(context: Context) = "Diagnostica truststore, proxy y conectividad con el proveedor activo"

    override fun run() {
        val parent = currentContext.parent?.command as CommonOptions
        val boot = try { parent.bootstrap() } catch (e: IllegalArgumentException) { echo("kotycli: ${e.message}", err = true); exitProcess(2) }
        val report = boot.httpAndReport.second
        echo("JVM:          ${System.getProperty("java.vendor")} ${System.getProperty("java.version")} en ${System.getProperty("os.name")}")
        echo("Config:       ${boot.config.sources.joinToString().ifEmpty { "(ninguna; valores por defecto)" }}")
        echo("Proveedor:    ${boot.config.providerName} (${boot.config.provider.type}) · modelo ${runCatching { boot.config.model }.getOrDefault("?")}")
        echo("Shell:        ${boot.shell.displayName}")
        echo("Truststore:   ${report.trustSources.joinToString(" + ")}")
        echo("Proxy:        ${report.proxyDescription}")
        val baseUrl = boot.config.provider.baseUrl
        if (baseUrl == null) { echo("Conectividad: el proveedor no tiene baseUrl"); return }
        val url = baseUrl.trimEnd('/') + "/models"
        echo("Conectividad: GET $url")
        try {
            val req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(java.time.Duration.ofSeconds(15)).build()
            val res = boot.http.send(req, HttpResponse.BodyHandlers.ofString())
            echo("              HTTP ${res.statusCode()} · ${res.body().take(300).replace('\n', ' ')}")
        } catch (e: Exception) {
            echo("              FALLO: ${e::class.simpleName}: ${e.message}")
            var cause = e.cause
            while (cause != null) { echo("              causa: ${cause::class.simpleName}: ${cause.message}"); cause = cause.cause }
            exitProcess(1)
        }
    }
}

fun main(args: Array<String>) {
    ensureUtf8Stdout()
    CommonOptions().subcommands(Doctor()).main(args)
}

/** La consola de Windows (y `java -jar` sin flags) no escribe UTF-8 por defecto. stdout es del frontend, así que se fija aquí. */
private fun ensureUtf8Stdout() {
    if (System.getProperty("stdout.encoding")?.equals("UTF-8", ignoreCase = true) == true) return
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
}
