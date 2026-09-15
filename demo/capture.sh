#!/usr/bin/env bash
# Captura una sesión de la TUI en un PTY de verdad.
# `stty` es imprescindible: el PTY que abre `script` no tiene tamaño, y sin tamaño JLine
# trunca el prompt y lo rellena con puntos.
set -u
out="$1"; shift
cd "$(dirname "$0")/medallion"
{ for line in "$@"; do sleep 3; printf '%s\n' "$line"; done; sleep 2; } \
  | TERM=xterm-256color timeout 180 script -qec \
      "stty rows 45 cols 118; java -jar ../../build/libs/kotycli.jar" "../$out" >/dev/null 2>&1
