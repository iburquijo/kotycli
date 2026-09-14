package com.softbenur.kotycli.tools

import com.softbenur.kotycli.FakeProvider
import com.softbenur.kotycli.testContext
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchToolTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/doc") { ex ->
            val body = """
                <html><head><title>Guía</title><style>p{}</style></head>
                <body><nav>menú</nav><h2>Instalación</h2><p>Ejecuta <code>./gradlew build</code> y listo.</p>
                <ul><li>Uno</li><li><a href="/otra">Otra página</a></li></ul>
                <pre>java -jar kotycli.jar</pre></body></html>
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/api") { ex ->
            val body = """{"ok":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/nope") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val fetch = FetchTool()
    private val ctx = ToolContext(testContext(FakeProvider(mutableListOf()), http = HttpClient.newHttpClient()), "1")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `el html vuelve como markdown sin navegacion ni estilos`() = runTest {
        val result = fetch.run(FetchInput("$base/doc"), ctx)
        assertFalse(result.isError, result.content)
        assertContains(result.content, "# Guía")
        assertContains(result.content, "## Instalación")
        assertContains(result.content, "`./gradlew build`")
        assertContains(result.content, "- [Otra página]($base/otra)")
        assertContains(result.content, "```\njava -jar kotycli.jar\n```")
        assertFalse(result.content.contains("menú"), result.content)
    }

    @Test
    fun `lo que no es html vuelve tal cual y raw desactiva la conversion`() = runTest {
        assertEquals("""{"ok":true}""", fetch.run(FetchInput("$base/api"), ctx).content)
        assertContains(fetch.run(FetchInput("$base/doc", raw = true), ctx).content, "<nav>")
    }

    @Test
    fun `esquemas no http, host inalcanzable y 404 vuelven como error`() = runTest {
        assertTrue(fetch.run(FetchInput("file:///etc/passwd"), ctx).isError)
        assertTrue(fetch.run(FetchInput("http://127.0.0.1:1/x"), ctx).isError)
        val notFound = fetch.run(FetchInput("$base/nope"), ctx)
        assertTrue(notFound.isError && notFound.content.contains("404"))
    }
}
