# kotycli
Estoy hasta los huevos de las clis de agentes que dependen del sistema operativo. La JVM ya lo resolvió hace tiempo. Así me lo monto a mi santo gusto.

## Estado

Usable: loop, tools `bash`, `read`, `edit`, `create`, `fetch` y `task` (subagentes), skills en `.agents/skills/`,
proveedor OpenAI-compatible (Ollama, gateways, vLLM, OpenRouter...), `HttpClient` único con truststore y proxy
corporativos, permisos como interceptor, y tres frontends: TUI append-only, `--plain` y `--acp` (Emacs, Zed,
Neovim). Falta el proveedor `copilot` y los comandos `/` de la TUI. Lo que queda por versión está en el
[roadmap](docs/architecture/07-build-y-distribucion.md#roadmap); el estado actual y el siguiente paso, en
[`docs/ESTADO.md`](docs/ESTADO.md).

## Uso

```
./gradlew build                      # tests + build/libs/kotycli.jar
java -jar build/libs/kotycli.jar     # sesión interactiva contra el proveedor de la config
java -jar build/libs/kotycli.jar --plain "explica qué hace este repo"
java -jar build/libs/kotycli.jar --acp         # JSON-RPC por stdio: lo arranca el editor, no tú
java -jar build/libs/kotycli.jar doctor
```

`--acp` no se usa a mano: lo lanza el editor. Cualquier cliente ACP (agent-shell y agent-ide en Emacs, Zed,
Neovim) sirve; se le configura como agente el comando `java -jar /ruta/a/kotycli.jar --acp` y él se encarga del
resto. Los errores de protocolo quedan en `~/.kotycli/logs/acp.log`.

Sin configuración usa un Ollama local (`http://localhost:11434/v1`). Para otro proveedor, `~/.kotycli/config.json` o `.kotycli/config.json` en el proyecto:

```json
{
  "provider": "corp",
  "providers": {
    "local": { "type": "openai", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:32b", "contextWindow": 32000 },
    "corp":  { "type": "openai", "baseUrl": "https://llm-gw.corp/v1", "model": "gpt-4.1", "apiKeyEnv": "CORP_LLM_KEY", "contextWindow": 128000 }
  },
  "permissionMode": "default",
  "http": { "truststore": "C:/certs/corp-ca.pem" }
}
```

Las claves nunca van en el fichero: solo el nombre de la variable de entorno. Reglas de permisos persistentes en `settings.json`, en los mismos directorios:

```json
{ "permissions": { "allow": ["bash(git status*)", "bash(./gradlew *)"], "deny": ["bash(rm -rf *)", "bash(git push --force*)"] } }
```

Opciones: `--provider`, `--model`, `--mode default|accept-edits|yolo`, `--cwd`, `--truststore`, `--allow-path`, `--plain`, `--acp`, `--yes`, `--max-tokens`.

## Arquitectura

La arquitectura del harness (loop, seis tools, subagentes, skills, proveedores, frontends TUI/plain/ACP y red corporativa) está en [`docs/architecture/`](docs/architecture/README.md). Las decisiones cerradas, en [`docs/adr/`](docs/adr/README.md). Las convenciones para tocar el código, en [`AGENTS.md`](AGENTS.md).
