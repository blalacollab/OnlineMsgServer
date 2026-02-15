#!/usr/bin/env bash
set -euo pipefail

# One-click deployment for test environments (WS mode, no forced WSS).
#
# Usage:
#   bash deploy/deploy_test_ws.sh
#
# Optional overrides:
#   CONTAINER_NAME=onlinemsgserver IMAGE_NAME=onlinemsgserver:latest HOST_PORT=13173 bash deploy/deploy_test_ws.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

CONTAINER_NAME="${CONTAINER_NAME:-onlinemsgserver}"
IMAGE_NAME="${IMAGE_NAME:-onlinemsgserver:latest}"
HOST_PORT="${HOST_PORT:-13173}"

for cmd in openssl docker ipconfig route awk base64 tr; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Missing required command: ${cmd}"
    exit 1
  fi
done

DEFAULT_IFACE="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
LAN_IP=""
if [ -n "${DEFAULT_IFACE}" ]; then
  LAN_IP="$(ipconfig getifaddr "${DEFAULT_IFACE}" 2>/dev/null || true)"
fi

if [ -z "${LAN_IP}" ]; then
  LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
fi
if [ -z "${LAN_IP}" ]; then
  LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
fi
if [ -z "${LAN_IP}" ]; then
  LAN_IP="127.0.0.1"
fi

mkdir -p "${ROOT_DIR}/deploy/keys"

# Generate service RSA key only if missing.
if [ ! -f "${ROOT_DIR}/deploy/keys/server_rsa_pkcs8.b64" ]; then
  echo "Generating service RSA key..."
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${ROOT_DIR}/deploy/keys/server_rsa.pem"
  openssl pkcs8 -topk8 -inform PEM \
    -in "${ROOT_DIR}/deploy/keys/server_rsa.pem" \
    -outform DER -nocrypt \
    -out "${ROOT_DIR}/deploy/keys/server_rsa_pkcs8.der"
  base64 < "${ROOT_DIR}/deploy/keys/server_rsa_pkcs8.der" | tr -d '\n' > "${ROOT_DIR}/deploy/keys/server_rsa_pkcs8.b64"
fi

echo "Building image: ${IMAGE_NAME}"
docker build -t "${IMAGE_NAME}" "${ROOT_DIR}"

echo "Restarting container: ${CONTAINER_NAME}"
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${CONTAINER_NAME}" --restart unless-stopped \
  -p "${HOST_PORT}:13173" \
  -v "${ROOT_DIR}/deploy/keys:/app/keys:ro" \
  -e REQUIRE_WSS=false \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  "${IMAGE_NAME}"

echo "Container logs (tail 30):"
docker logs --tail 30 "${CONTAINER_NAME}"

echo
echo "Done."
echo "Frontend URL (LAN): ws://${LAN_IP}:${HOST_PORT}/"
echo "Frontend URL (local): ws://localhost:${HOST_PORT}/"
