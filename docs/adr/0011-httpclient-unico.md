# ADR 0011. Un único HttpClient con truststore y proxy corporativos

**Estado:** aceptado

## Contexto

En el entorno de trabajo hay un proxy con inspección TLS que presenta certificados de una CA interna. Cada herramienta ajena falla aquí de una forma distinta: un backend Node que no lee el almacén de Windows, un runtime Python con su propio bundle de CAs, flags `--insecure` que nadie quiere usar.

## Decisión

- Un solo `java.net.http.HttpClient` construido en `http/` al arrancar. Lo usan todos los adaptadores de proveedor y la tool `fetch`. Nadie más crea clientes HTTP.
- Truststore: se **añaden** al `cacerts` del JDK las CAs de `--truststore` / `KOTYCLI_CA_BUNDLE` y, en Windows, el almacén del sistema (`Windows-ROOT`). Nunca se desactiva la verificación TLS; no hay `--insecure`.
- Proxy: config, variables `HTTPS_PROXY`/`NO_PROXY`, y en Windows el proxy del sistema (`useSystemProxies`).
- HTTP/1.1 por defecto: HTTP/2 a través de proxies inspectores da más problemas que ventajas.
- `kotycli doctor` diagnostica truststore, proxy y conectividad al proveedor activo.

## Alternativas descartadas

- **Un cliente HTTP por adaptador o el que traiga cada SDK**: cada uno con su propio truststore y su propia resolución de proxy. Es exactamente el problema que queremos resolver una vez.
- **Ktor client o OkHttp**: buenas librerías, pero el del JDK ya está, lee el almacén de Windows sin dependencias nativas y una dependencia menos en el fat jar.

## Consecuencias

- Si el adaptador `anthropic` usa el SDK oficial, tiene que poder inyectarle este cliente o su `SSLContext` y proxy. Si no puede, se hace con wire propio.
- El diagnóstico de red es un comando de primera clase, no un log.
