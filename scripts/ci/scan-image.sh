#!/usr/bin/env bash
# Analiza una imagen Docker (archivo .tar de `docker save`) con Trivy y FALLA ante vulnerabilidades corregibles
# de severidad HIGH o CRITICAL. No publica nada y no necesita el socket de Docker.
#
# Criterio: --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1. Se ignoran las vulnerabilidades sin
# correccion publicada (no hay accion posible); si aparece una corregible, el analisis falla.
# Si Trivy no puede descargar su base de datos, el comando FALLA: no se considera un analisis satisfactorio.
#
# Uso: bash scripts/ci/scan-image.sh ruta/imagen.tar
set -euo pipefail

# Evita que Git Bash (Windows) reescriba rutas como /image.tar; no afecta a Linux.
export MSYS_NO_PATHCONV=1

TRIVY_IMAGE="aquasec/trivy:0.75.0"
TAR="${1:?Indique el archivo .tar de la imagen}"
[ -f "$TAR" ] || { echo "::error::No existe $TAR"; exit 1; }
CACHE="${TRIVY_CACHE_DIR:-$HOME/.cache/trivy-ci}"
mkdir -p "$CACHE"

TAR_DIR="$(cd "$(dirname "$TAR")" && pwd)"
TAR_NAME="$(basename "$TAR")"

docker run --rm \
  -v "$TAR_DIR/$TAR_NAME:/image.tar:ro" \
  -v "$CACHE:/root/.cache/" \
  "$TRIVY_IMAGE" image \
  --input /image.tar \
  --severity HIGH,CRITICAL \
  --ignore-unfixed \
  --exit-code 1 \
  --no-progress
