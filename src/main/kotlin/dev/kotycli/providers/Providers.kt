package dev.kotycli.providers

import dev.kotycli.config.ProviderConfig
import dev.kotycli.providers.openai.Authenticator
import dev.kotycli.providers.openai.OpenAiProvider
import java.net.http.HttpClient

/** Único sitio fuera de `providers/<x>/` que conoce los adaptadores concretos. */
object Providers {
    fun build(name: String, cfg: ProviderConfig, http: HttpClient, env: Map<String, String> = System.getenv()): Provider {
        val capabilities = Capabilities(
            parallelToolCalls = cfg.parallelToolCalls,
            allowsHistoryEdits = cfg.allowsHistoryEdits,
            contextWindow = cfg.contextWindow,
        )
        return when (cfg.type) {
            "openai" -> {
                val baseUrl = cfg.baseUrl ?: throw IllegalArgumentException("El proveedor '$name' (openai) necesita `baseUrl`")
                val auth = cfg.apiKeyEnv?.let { varName ->
                    val key = env[varName] ?: throw IllegalArgumentException("El proveedor '$name' espera la clave en la variable de entorno $varName, que no está definida")
                    Authenticator.bearer(key)
                } ?: Authenticator.NONE
                OpenAiProvider(http, baseUrl, auth, id = name, capabilities = capabilities)
            }
            "copilot" -> throw IllegalArgumentException("El proveedor 'copilot' llega en v2 (ADR 0012). Usa un endpoint OpenAI-compatible mientras tanto")
            "anthropic" -> throw IllegalArgumentException("El proveedor 'anthropic' aún no está implementado")
            else -> throw IllegalArgumentException("Tipo de proveedor desconocido: '${cfg.type}' (válidos: openai)")
        }
    }
}
