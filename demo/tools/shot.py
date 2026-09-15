"""Convierte una captura ANSI de terminal en un PNG.

La captura se hace con `script`, que da un PTY de verdad: así se fotografía lo que
ve el usuario, con sus colores, no una redirección a fichero.
"""
import re
import sys
from pathlib import Path

from ansi2html import Ansi2HTMLConverter
from playwright.sync_api import sync_playwright

# Ruido que no es de kotycli: la cabecera de `script` y el aviso del proxy del contenedor.
NOISE = re.compile(r"^(Script (started|done)|Picked up JAVA_TOOL_OPTIONS).*$\n?", re.MULTILINE)
OSC = re.compile(r"\x1b\][0-9]*;[^\x07\x1b]*(?:\x07|\x1b\\)")
# De las secuencias CSI solo interesa el color (SGR, la que acaba en `m`); el resto es movimiento de cursor.
NON_SGR = re.compile(r"\x1b\[[0-?]*[ -/]*(?![m])[@-~]")
# `\x1b=` y `\x1b>` son el modo teclado que pone y quita JLine: invisibles en un terminal, basura en una imagen.
OTHER_ESC = re.compile(r"\x1b[=>()][0-9A-B]?")


def clean(raw: str) -> str:
    text = NOISE.sub("", raw)
    text = OSC.sub("", text)
    text = NON_SGR.sub("", text)
    text = OTHER_ESC.sub("", text)
    return text.replace("\r\n", "\n").replace("\r", "")


def main(src: Path, dst: Path, title: str) -> None:
    text = clean(src.read_text(encoding="utf-8", errors="replace"))
    body = Ansi2HTMLConverter(dark_bg=True, scheme="osx").convert(text, full=False)
    html = f"""<!doctype html><meta charset="utf-8"><style>
      body {{ margin: 0; background: #1b1b1f; font-family: ui-monospace, "DejaVu Sans Mono", monospace; }}
      .chrome {{ background: #2b2b31; color: #cfcfd6; padding: 8px 14px; font-size: 13px;
                 border-bottom: 1px solid #3a3a42; }}
      .dot {{ display: inline-block; width: 11px; height: 11px; border-radius: 50%; margin-right: 6px; }}
      pre {{ margin: 0; padding: 16px 18px; color: #e6e6ea; font-size: 13.5px; line-height: 1.45;
             white-space: pre-wrap; word-break: break-word; }}
    </style>
    <div class="chrome"><span class="dot" style="background:#ff5f57"></span>
      <span class="dot" style="background:#febc2e"></span>
      <span class="dot" style="background:#28c840"></span>&nbsp; {title}</div>
    <pre>{body}</pre>"""
    out = dst.resolve().with_suffix(".html")
    out.write_text(html, encoding="utf-8")
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path="/opt/pw-browsers/chromium-1194/chrome-linux/chrome")
        page = browser.new_page(viewport={"width": 1100, "height": 700}, device_scale_factor=2)
        page.goto(out.as_uri())
        page.screenshot(path=str(dst), full_page=True)
        browser.close()
    out.unlink()
    print(f"{dst} ({dst.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else "kotycli")
