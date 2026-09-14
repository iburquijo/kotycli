package dev.kotycli.http

import dev.kotycli.tools.Shell
import java.io.FileInputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@kotlinx.serialization.Serializable
data class HttpConfig(
    val truststore: String? = null,
    val proxy: String? = null,
    val connectTimeoutSec: Int = 20,
)

/** Qué se resolvió, para `kotycli doctor`. */
data class HttpReport(
    val trustSources: List<String>,
    val proxyDescription: String,
)

/**
 * El único `HttpClient` del proceso (ADR 0011). Truststore corporativo y proxy resueltos una vez;
 * proveedores y `fetch` lo heredan. Nunca se desactiva la verificación TLS.
 */
object Http {
    fun build(cfg: HttpConfig, env: Map<String, String> = System.getenv()): Pair<HttpClient, HttpReport> {
        val (ssl, trustSources) = TrustStores.corporate(cfg, env)
        val (selector, proxyDescription) = Proxies.resolve(cfg, env)
        val builder = HttpClient.newBuilder()
            .sslContext(ssl)
            .proxy(selector)
            .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSec.toLong()))
            .version(HttpClient.Version.HTTP_1_1)
        Proxies.authenticator(cfg, env)?.let { builder.authenticator(it) }
        return builder.build() to HttpReport(trustSources, proxyDescription)
    }
}

object TrustStores {
    /** Combina el `cacerts` del JDK con las CAs de config, `KOTYCLI_CA_BUNDLE` y, en Windows, el almacén del sistema. */
    fun corporate(cfg: HttpConfig, env: Map<String, String>): Pair<SSLContext, List<String>> {
        val managers = mutableListOf<X509TrustManager>()
        val sources = mutableListOf<String>()

        fun add(label: String, certs: Collection<X509Certificate>) {
            if (certs.isEmpty()) return
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> ks.setCertificateEntry("$label-$i", c) }
            managers += trustManagerFor(ks)
            sources += "$label (${certs.size} CAs)"
        }

        cfg.truststore?.let { add("config:$it", loadCerts(Path.of(it))) }
        env["KOTYCLI_CA_BUNDLE"]?.takeIf { it.isNotBlank() }?.let { add("KOTYCLI_CA_BUNDLE:$it", loadCerts(Path.of(it))) }
        if (Shell.isWindows) {
            runCatching {
                val ks = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
                add("Windows-ROOT", ks.aliases().toList().mapNotNull { ks.getCertificate(it) as? X509Certificate })
            }
        }
        managers += trustManagerFor(null)
        sources += "JDK cacerts"

        val combined = CombinedTrustManager(managers)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(combined), null) }
        return ctx to sources
    }

    fun loadCerts(path: Path): List<X509Certificate> {
        require(Files.isRegularFile(path)) { "No existe el truststore: $path" }
        val name = path.fileName.toString().lowercase()
        val keystoreType = when {
            name.endsWith(".jks") -> "JKS"
            name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12") -> "PKCS12"
            else -> null
        }
        if (keystoreType != null) {
            val ks = KeyStore.getInstance(keystoreType)
            FileInputStream(path.toFile()).use { ks.load(it, null) }
            return ks.aliases().toList().mapNotNull { ks.getCertificate(it) as? X509Certificate }
        }
        val cf = CertificateFactory.getInstance("X.509")
        return FileInputStream(path.toFile()).use { cf.generateCertificates(it) }.map { it as X509Certificate }
    }

    private fun trustManagerFor(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Acepta si cualquiera de los managers acepta. Se añaden CAs, nunca se sustituyen. */
    class CombinedTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = check { it.checkClientTrusted(chain, authType) }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check { it.checkServerTrusted(chain, authType) }
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()

        private fun check(block: (X509TrustManager) -> Unit) {
            var last: CertificateException? = null
            for (d in delegates) {
                try { block(d); return } catch (e: CertificateException) { last = e }
            }
            throw last ?: CertificateException("Sin trust managers")
        }
    }
}

object Proxies {
    /** Config, luego `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY`, luego el selector por defecto de la JVM (que en Windows lee el proxy del sistema). */
    fun resolve(cfg: HttpConfig, env: Map<String, String>): Pair<ProxySelector, String> {
        cfg.proxy?.takeIf { it.isNotBlank() }?.let { return EnvProxySelector(URI(it), noProxy(env)) to "config: $it" }
        val fromEnv = env["HTTPS_PROXY"] ?: env["https_proxy"] ?: env["HTTP_PROXY"] ?: env["http_proxy"]
        if (!fromEnv.isNullOrBlank()) {
            val uri = URI(if (fromEnv.contains("://")) fromEnv else "http://$fromEnv")
            val noProxy = noProxy(env)
            return EnvProxySelector(uri, noProxy) to "entorno: ${uri.host}:${uri.port}" + (if (noProxy.isEmpty()) "" else " (NO_PROXY: ${noProxy.joinToString()})")
        }
        if (Shell.isWindows && System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true")
        val sysHost = System.getProperty("https.proxyHost") ?: System.getProperty("http.proxyHost")
        val description = when {
            sysHost != null -> "propiedades de la JVM: $sysHost"
            Shell.isWindows -> "proxy del sistema Windows (useSystemProxies)"
            else -> "directo"
        }
        return ProxySelector.getDefault() to description
    }

    fun authenticator(cfg: HttpConfig, env: Map<String, String>): Authenticator? {
        val raw = cfg.proxy ?: env["HTTPS_PROXY"] ?: env["https_proxy"] ?: env["HTTP_PROXY"] ?: env["http_proxy"] ?: return null
        val userInfo = runCatching { URI(if (raw.contains("://")) raw else "http://$raw").userInfo }.getOrNull() ?: return null
        val user = userInfo.substringBefore(':')
        val pass = userInfo.substringAfter(':', "")
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestorType == RequestorType.PROXY) PasswordAuthentication(user, pass.toCharArray()) else null
        }
    }

    private fun noProxy(env: Map<String, String>): List<String> =
        (env["NO_PROXY"] ?: env["no_proxy"]).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    class EnvProxySelector(proxyUri: URI, private val noProxy: List<String>) : ProxySelector() {
        private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyUri.host, if (proxyUri.port > 0) proxyUri.port else 80))

        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (bypass(host)) return listOf(Proxy.NO_PROXY)
            return listOf(proxy)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {}

        fun bypass(host: String): Boolean {
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            return noProxy.any { entry ->
                val e = entry.removePrefix(".").removePrefix("*.")
                entry == "*" || host.equals(e, ignoreCase = true) || host.endsWith(".$e", ignoreCase = true)
            }
        }
    }
}
