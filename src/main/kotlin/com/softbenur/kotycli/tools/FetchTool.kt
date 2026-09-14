package com.softbenur.kotycli.tools

import com.softbenur.kotycli.core.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class FetchInput(
    @Description("URL http o https a descargar")
    val url: String,
    @Description("Devuelve el cuerpo tal cual en vez de convertir el HTML a markdown")
    val raw: Boolean = false,
)

/** GET sobre el `HttpClient` común (truststore y proxy ya resueltos) y HTML a markdown con jsoup. */
class FetchTool : TypedTool<FetchInput>(FetchInput.serializer()) {
    override val name = "fetch"
    override val readOnly = true
    override val timeout: Duration = 30.seconds
    override val description = """
        Descarga una URL y devuelve su contenido. El HTML vuelve convertido a markdown; el resto (JSON, texto
        plano) tal cual. Solo http y https, solo GET. Úsalo para documentación y APIs públicas, no para navegar.
    """.trimIndent()

    override suspend fun run(input: FetchInput, ctx: ToolContext): Block.ToolResult {
        val uri = try { URI.create(input.url.trim()) } catch (e: IllegalArgumentException) { return ctx.error("URL inválida: ${input.url}") }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return ctx.error("Solo se permiten http y https, no '${uri.scheme}'")
        val http = ctx.env.http ?: return ctx.error("Esta sesión no tiene cliente HTTP")

        val request = HttpRequest.newBuilder(uri)
            .GET()
            .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.8")
            .header("User-Agent", "kotycli")
            .timeout(java.time.Duration.ofSeconds(timeout.inWholeSeconds))
            .build()
        val response = try {
            runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        } catch (e: Exception) {
            return ctx.error("No se pudo descargar $uri: ${e::class.simpleName}: ${e.message}")
        }

        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        val body = response.body().take(MAX_BYTES)
        val text = if (!input.raw && contentType.startsWith("text/html")) Html.toMarkdown(body, uri.toString()) else body
        val truncated = if (response.body().length > MAX_BYTES) "\n[descarga cortada en ${MAX_BYTES / 1024} KB]" else ""
        return if (response.statusCode() in 200..299) ctx.ok(text + truncated)
        else ctx.error("HTTP ${response.statusCode()} en $uri\n${text.take(2000)}")
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
    }
}

/** Conversión mínima de HTML a markdown: lo justo para leer documentación sin ruido de navegación. */
internal object Html {
    private val SKIP = setOf("script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg")

    fun toMarkdown(html: String, baseUri: String): String {
        val document = Jsoup.parse(html, baseUri)
        document.select(SKIP.joinToString(",")).remove()
        val sb = StringBuilder()
        document.title().takeIf { it.isNotBlank() }?.let { sb.append("# ").append(it).append("\n\n") }
        render(document.body(), sb)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun render(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text().replace(Regex("\\s+"), " "))
            is Element -> when (node.tagName()) {
                "br" -> sb.append('\n')
                "hr" -> sb.append("\n---\n")
                "h1", "h2", "h3", "h4", "h5", "h6" -> block(node, sb, "\n${"#".repeat(node.tagName().last().digitToInt())} ", "\n")
                "p", "div", "section", "article", "tr" -> block(node, sb, "\n", "\n")
                "li" -> block(node, sb, "\n- ", "")
                "blockquote" -> block(node, sb, "\n> ", "\n")
                "pre" -> sb.append("\n```\n").append(node.wholeText().trim()).append("\n```\n")
                "code" -> sb.append('`').append(node.text()).append('`')
                "a" -> {
                    val href = node.attr("abs:href")
                    val label = node.text().trim()
                    if (label.isEmpty()) return
                    if (href.isBlank() || href.startsWith("javascript:")) sb.append(label) else sb.append("[$label]($href)")
                }
                "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { sb.append("![$it]") }
                else -> node.childNodes().forEach { render(it, sb) }
            }
        }
    }

    private fun block(node: Element, sb: StringBuilder, prefix: String, suffix: String) {
        sb.append(prefix)
        node.childNodes().forEach { render(it, sb) }
        sb.append(suffix)
    }
}
