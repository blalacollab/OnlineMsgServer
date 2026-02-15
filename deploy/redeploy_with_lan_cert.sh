#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   bash deploy/redeploy_with_lan_cert.sh
#
# Optional overrides:
#   CONTAINER_NAME=onlinemsgserver IMAGE_NAME=onlinemsgserver:latest CERT_PASSWORD=changeit bash deploy/redeploy_with_lan_cert.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

CONTAINER_NAME="${CONTAINER_NAME:-onlinemsgserver}"
IMAGE_NAME="${IMAGE_NAME:-onlinemsgserver:latest}"
CERT_PASSWORD="${CERT_PASSWORD:-changeit}"

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
  echo "Failed to detect LAN IP from default interface/en0/en1."
  exit 1
fi

echo "LAN IP: ${LAN_IP}"

mkdir -p "${ROOT_DIR}/deploy/certs" "${ROOT_DIR}/deploy/keys"

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

echo "Reissuing TLS certificate with LAN SAN..."
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 365 \
  -subj "/CN=${LAN_IP}" \
  -addext "subjectAltName=IP:${LAN_IP},IP:127.0.0.1,DNS:localhost" \
  -keyout "${ROOT_DIR}/deploy/certs/tls.key" \
  -out "${ROOT_DIR}/deploy/certs/tls.crt"

openssl pkcs12 -export \
  -inkey "${ROOT_DIR}/deploy/certs/tls.key" \
  -in "${ROOT_DIR}/deploy/certs/tls.crt" \
  -out "${ROOT_DIR}/deploy/certs/server.pfx" \
  -passout "pass:${CERT_PASSWORD}"

echo "Rebuilding image: ${IMAGE_NAME}"
docker build -t "${IMAGE_NAME}" "${ROOT_DIR}"

echo "Restarting container: ${CONTAINER_NAME}"
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d --name "${CONTAINER_NAME}" --restart unless-stopped \
  -p 13173:13173 \
  -v "${ROOT_DIR}/deploy/certs:/app/certs:ro" \
  -v "${ROOT_DIR}/deploy/keys:/app/keys:ro" \
  -e REQUIRE_WSS=true \
  -e TLS_CERT_PATH=/app/certs/server.pfx \
  -e TLS_CERT_PASSWORD="${CERT_PASSWORD}" \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  "${IMAGE_NAME}"

echo "Container logs (tail 30):"
docker logs --tail 30 "${CONTAINER_NAME}"

echo
echo "Done."
echo "Use this URL in frontend: wss://${LAN_IP}:13173/"
echo "If using self-signed cert, trust deploy/certs/tls.crt on client devices."
