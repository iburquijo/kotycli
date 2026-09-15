#!/usr/bin/env bash
# Lanza kotycli contra OpenRouter y guarda la transcripción en logs/.
# El token se lee de $OPENROUTER_TOKEN; nunca se escribe en ningún sitio.
set -u
name="$1"; shift
cd "$(dirname "$0")/medallion"
stdbuf -oL -eL java -jar ../../build/libs/kotycli.jar --plain --yes --mode yolo "$*" 2>&1 \
  | grep --line-buffered -v "^Picked up" \
  | stdbuf -oL tee "../logs/${name}.log"
