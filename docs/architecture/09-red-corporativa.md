# 09. Red corporativa: proxy, truststore y Windows

Este documento existe porque es la capa que ha fallado en todas las herramientas ajenas. Aquí se resuelve una vez, en un sitio, y todo lo demás lo hereda.

## Un solo `HttpClient`

`http/Http.kt` construye un único `java.net.http.HttpClient` al arrancar y lo inyecta a los adaptadores de proveedor y a la tool `fetch`. Nadie más crea clientes HTTP.

```kotlin
object Http {
    fun build(cfg: HttpConfig): HttpClient = HttpClient.newBuilder()
        .sslContext(TrustStores.corporate(cfg))       // ver abajo
        .proxy(Proxies.resolve(cfg))                  // ver abajo
        .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSec))
        .version(HttpClient.Version.HTTP_1_1)         // HTTP/2 a través de proxies inspectores da más problemas que ventajas
        .build()
}
```

## Truststore

El proxy corporativo hace inspección TLS y presenta certificados firmados por una CA interna. Si la JVM no la conoce, todo falla con `PKIX path building failed`.

Orden de resolución:

1. `--truststore <path>` o `http.truststore` en config: un fichero PEM o JKS/PKCS12 con las CAs corporativas.
2. Variable `KOTYCLI_CA_BUNDLE` (PEM), mismo espíritu que `SSL_CERT_FILE` o `NODE_EXTRA_CA_CERTS`.
3. **Windows**: el almacén del sistema vía `KeyStore.getInstance("Windows-ROOT")`. Las CAs corporativas normalmente ya están ahí, empujadas por directiva. Este es el caso feliz en el trabajo y el motivo principal para no usar un cliente HTTP que traiga su propio truststore.
4. El `cacerts` del JDK.

Los certificados de 1 a 3 se **añaden** a los del JDK, no los sustituyen. Se construye un `SSLContext` con un `TrustManager` combinado. Nunca se desactiva la verificación TLS: no hay flag `--insecure` y no la va a haber.

## Proxy

Orden de resolución:

1. `http.proxy` en config.
2. `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` de entorno (en mayúsculas y minúsculas, como hace todo el mundo).
3. **Windows**: `ProxySelector.getDefault()` con `java.net.useSystemProxies=true`, que lee la configuración de Internet Options / WinHTTP. Es lo que usa el navegador, así que es lo que funciona.
4. Directo.

`NO_PROXY` se respeta para `localhost` y para Ollama en casa. Autenticación de proxy: `Authenticator` con usuario y contraseña de config o de `HTTPS_PROXY` con credenciales en la URL. Kerberos/NTLM fuera del alcance hasta que haga falta.

## Diagnóstico

`kotycli doctor` imprime qué truststore se cargó y cuántas CAs tiene, qué proxy se resolvió y para qué hosts, y hace un `GET` de prueba al `baseUrl` del proveedor activo mostrando el error TLS o HTTP completo si falla. Es el primer comando que se ejecuta en una máquina nueva y el que ahorra la tarde de depuración.

## Windows en el resto del harness

- **Shell de `bash`**: se detecta al arrancar en este orden: Git Bash (`bash.exe` en `Program Files\Git`), PowerShell 7 (`pwsh`), Windows PowerShell. Se fija para toda la sesión y se dice en la description de la tool y en el system prompt. El modelo no tiene que adivinar.
- **Matar procesos**: `Process.destroy()` en Windows no mata a los hijos. Siempre `ProcessHandle.descendants()` + `destroyForcibly()` en orden inverso.
- **Line endings**: `edit` normaliza a `\n` para comparar y escribe con el line ending que tenía el fichero. `create` escribe `\n` salvo que `.gitattributes` o config digan otra cosa.
- **Paths**: `Path.toRealPath()` para canonicalizar; comparación case-insensitive en Windows para la guarda de working dir. Nunca se comparan strings de path.
- **Consola**: la TUI usa JLine con el terminal de Windows nativo (ConPTY en Windows Terminal). Sin librerías nativas extra. `--plain` es la salida de emergencia si algo del render falla.
- **Codificación**: stdout y stdin en UTF-8 explícito (`-Dfile.encoding=UTF-8` y `-Dstdout.encoding=UTF-8` en el manifest o el launcher). La consola de Windows por defecto no lo es.
