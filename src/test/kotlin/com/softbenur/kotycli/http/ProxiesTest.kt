package com.softbenur.kotycli.http

import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxiesTest {
    @Test
    fun `la config gana al entorno y NO_PROXY se respeta`() {
        val (selector, description) = Proxies.resolve(HttpConfig(proxy = "http://proxy.corp:3128"), mapOf("HTTPS_PROXY" to "http://otro:1", "NO_PROXY" to ".corp.local, localhost"))
        assertTrue(description.startsWith("config"))
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://api.example.com/v1")).single().type())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost:11434/v1")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://llm.corp.local/v1")).single())
    }

    @Test
    fun `HTTPS_PROXY del entorno con credenciales alimenta el authenticator`() {
        val env = mapOf("https_proxy" to "http://user:secret@proxy:8080")
        val (selector, _) = Proxies.resolve(HttpConfig(), env)
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://x.y")).single().type())
        assertTrue(Proxies.authenticator(HttpConfig(), env) != null)
        assertTrue(Proxies.authenticator(HttpConfig(), mapOf("HTTPS_PROXY" to "http://proxy:8080")) == null)
    }

    @Test
    fun `el truststore combinado construye un SSLContext con el cacerts del JDK`() {
        val (_, sources) = TrustStores.corporate(HttpConfig(), emptyMap())
        assertTrue(sources.any { it.contains("cacerts") })
    }
}
