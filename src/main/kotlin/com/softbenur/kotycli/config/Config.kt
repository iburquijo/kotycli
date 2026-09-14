package com.softbenur.kotycli.config

import com.softbenur.kotycli.http.HttpConfig
import com.softbenur.kotycli.interceptors.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ProviderConfig(
    val type: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKeyEnv: String? = null,
    val contextWindow: Int = 0,
    val parallelToolCalls: Boolean = true,
    val allowsHistoryEdits: Boolean = true,
    val maxOutputTokens: Int = 8192,
)

@Serializable
data class ConfigFile(
    val provider: String? = null,
    val model: String? = null,
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val subagentModel: String? = null,
    val http: HttpConfig? = null,
    val permissionMode: String? = null,
    val maxTokensPerSession: Int? = null,
    val maxIterationsPerTurn: Int? = null,
) {
    /** El fichero de proyecto pisa al de usuario campo a campo; los mapas de proveedores se fusionan. */
    fun overlay(other: ConfigFile) = ConfigFile(
        provider = other.provider ?: provider,
        model = other.model ?: model,
        providers = providers + other.providers,
        subagentModel = other.subagentModel ?: subagentModel,
        http = other.http ?: http,
        permissionMode = other.permissionMode ?: permissionMode,
        maxTokensPerSession = other.maxTokensPerSession ?: maxTokensPerSession,
        maxIterationsPerTurn = other.maxIterationsPerTurn ?: maxIterationsPerTurn,
    )
}

@Serializable
data class PermissionRules(val allow: List<String> = emptyList(), val ask: List<String> = emptyList(), val deny: List<String> = emptyList())

@Serializable
data class SettingsFile(val permissions: PermissionRules = PermissionRules()) {
    fun overlay(other: SettingsFile) = SettingsFile(
        PermissionRules(
            allow = permissions.allow + other.permissions.allow,
            ask = permissions.ask + other.permissions.ask,
            deny = permissions.deny + other.permissions.deny,
        )
    )

    fun rules(): List<Rule> =
        permissions.deny.map { Rule.parse(it, Rule.Kind.DENY) } +
            permissions.allow.map { Rule.parse(it, Rule.Kind.ALLOW) } +
            permissions.ask.map { Rule.parse(it, Rule.Kind.ASK) }
}

/** Dónde vive cada cosa en disco. `.kotycli/` es lo único con nombre propio (ADR 0013). */
class Dirs(val home: Path = Path.of(System.getProperty("user.home")), val project: Path) {
    val userConfigDir: Path get() = home.resolve(".kotycli")
    val projectConfigDir: Path get() = project.resolve(".kotycli")
    val logsDir: Path get() = userConfigDir.resolve("logs")
    val userAgentsDir: Path get() = home.resolve(".agents")
    val projectAgentsDir: Path get() = project.resolve(".agents")
}

data class Config(
    val file: ConfigFile,
    val settings: SettingsFile,
    val dirs: Dirs,
    val sources: List<Path>,
) {
    val providerName: String get() = file.provider ?: DEFAULT_PROVIDER
    val provider: ProviderConfig
        get() = file.providers[providerName]
            ?: throw IllegalArgumentException("Proveedor '$providerName' no definido en `providers` (${sources.joinToString().ifEmpty { "sin ficheros de config" }})")
    val model: String
        get() = file.model ?: provider.model
            ?: throw IllegalArgumentException("No hay modelo configurado: pon `model` en la config o usa --model")
    val http: HttpConfig get() = file.http ?: HttpConfig()

    companion object {
        const val DEFAULT_PROVIDER = "local"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

        /** Sin ficheros de config el harness funciona contra un Ollama local. */
        val defaults = ConfigFile(
            provider = DEFAULT_PROVIDER,
            providers = mapOf(
                DEFAULT_PROVIDER to ProviderConfig(type = "openai", baseUrl = "http://localhost:11434/v1", model = "qwen2.5-coder:7b", contextWindow = 32_000),
            ),
        )

        fun load(dirs: Dirs, overrides: ConfigFile = ConfigFile()): Config {
            val sources = mutableListOf<Path>()
            var cfg = defaults
            var settings = SettingsFile()
            for (dir in listOf(dirs.userConfigDir, dirs.projectConfigDir)) {
                readJson(dir.resolve("config.json"), ConfigFile.serializer())?.let { cfg = cfg.overlay(it); sources.add(dir.resolve("config.json")) }
                readJson(dir.resolve("settings.json"), SettingsFile.serializer())?.let { settings = settings.overlay(it); sources.add(dir.resolve("settings.json")) }
            }
            return Config(cfg.overlay(overrides), settings, dirs, sources)
        }

        private fun <T> readJson(path: Path, serializer: kotlinx.serialization.KSerializer<T>): T? {
            if (!Files.isRegularFile(path)) return null
            return try {
                json.decodeFromString(serializer, Files.readString(path))
            } catch (e: Exception) {
                throw IllegalArgumentException("No se pudo leer $path: ${e.message}", e)
            }
        }
    }
}
