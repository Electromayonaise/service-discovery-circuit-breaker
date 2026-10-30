#!/usr/bin/env bash
# Descarga el agente Java de OpenTelemetry en la raiz del repositorio (ignorado por git).
# Uso:  ./scripts/download-otel-agent.sh [version]     (por defecto la misma que usan los Dockerfiles)
set -euo pipefail
version="${1:-2.32.0}"
dest="$(cd "$(dirname "$0")/.." && pwd)/opentelemetry-javaagent.jar"
curl -fL -o "$dest" "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${version}/opentelemetry-javaagent.jar"
echo "Agente v${version} descargado en $dest"
