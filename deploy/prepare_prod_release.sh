#!/usr/bin/env bash
set -euo pipefail

# Prepare production deployment artifacts:
# 1) Prepare TLS certificate (import existing PEM cert/key, or optional self-signed fallback)
# 2) Prepare server RSA key (for message protocol)
# 3) Build production Docker image
# 4) Export image tar + env template for deployment
#
# Usage examples:
#   # Recommended: use real certificate files
#   DOMAIN=chat.example.com \
#   TLS_CERT_PEM=/path/fullchain.pem \
#   TLS_KEY_PEM=/path/privkey.pem \
#   TLS_CHAIN_PEM=/path/chain.pem \
#   CERT_PASSWORD='change-me' \
#   bash deploy/prepare_prod_release.sh
#
#   # Fallback (not for internet production): generate self-signed cert
#   DOMAIN=chat.example.com \
#   SAN_LIST='DNS:www.chat.example.com,IP:10.0.0.8' \
#   GENERATE_SELF_SIGNED=true \
#   CERT_PASSWORD='change-me' \
#   bash deploy/prepare_prod_release.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DOMAIN="${DOMAIN:-}"
SAN_LIST="${SAN_LIST:-}"
GENERATE_SELF_SIGNED="${GENERATE_SELF_SIGNED:-false}"
CERT_DAYS="${CERT_DAYS:-365}"

TLS_CERT_PEM="${TLS_CERT_PEM:-}"
TLS_KEY_PEM="${TLS_KEY_PEM:-}"
TLS_CHAIN_PEM="${TLS_CHAIN_PEM:-}"

CERT_PASSWORD="${CERT_PASSWORD:-changeit}"
IMAGE_NAME="${IMAGE_NAME:-onlinemsgserver}"
IMAGE_TAG="${IMAGE_TAG:-prod}"
IMAGE_REF="${IMAGE_NAME}:${IMAGE_TAG}"
EXPORT_IMAGE_TAR="${EXPORT_IMAGE_TAR:-true}"

OUTPUT_DIR="${OUTPUT_DIR:-${ROOT_DIR}/deploy/output/prod}"
CERT_DIR="${ROOT_DIR}/deploy/certs"
KEY_DIR="${ROOT_DIR}/deploy/keys"

for cmd in openssl docker base64 tr; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Missing required command: ${cmd}"
    exit 1
  fi
done

mkdir -p "${OUTPUT_DIR}" "${CERT_DIR}" "${KEY_DIR}"

if [ -z "${DOMAIN}" ]; then
  echo "DOMAIN is required. Example: DOMAIN=chat.example.com"
  exit 1
fi

echo "Preparing production artifacts for domain: ${DOMAIN}"

# 1) Prepare service RSA key for protocol
if [ ! -f "${KEY_DIR}/server_rsa_pkcs8.b64" ]; then
  echo "Generating protocol RSA key..."
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${KEY_DIR}/server_rsa.pem"
  openssl pkcs8 -topk8 -inform PEM \
    -in "${KEY_DIR}/server_rsa.pem" \
    -outform DER -nocrypt \
    -out "${KEY_DIR}/server_rsa_pkcs8.der"
  base64 < "${KEY_DIR}/server_rsa_pkcs8.der" | tr -d '\n' > "${KEY_DIR}/server_rsa_pkcs8.b64"
else
  echo "Protocol RSA key already exists, reusing: ${KEY_DIR}/server_rsa_pkcs8.b64"
fi

# 2) Prepare TLS material
TMP_CERT="${OUTPUT_DIR}/tls.crt"
TMP_KEY="${OUTPUT_DIR}/tls.key"
TMP_CHAIN="${OUTPUT_DIR}/chain.crt"
TMP_FULLCHAIN="${OUTPUT_DIR}/fullchain.crt"

if [ -n "${TLS_CERT_PEM}" ] || [ -n "${TLS_KEY_PEM}" ]; then
  if [ -z "${TLS_CERT_PEM}" ] || [ -z "${TLS_KEY_PEM}" ]; then
    echo "TLS_CERT_PEM and TLS_KEY_PEM must be set together."
    exit 1
  fi
  if [ ! -f "${TLS_CERT_PEM}" ] || [ ! -f "${TLS_KEY_PEM}" ]; then
    echo "TLS cert/key file not found."
    exit 1
  fi

  echo "Using provided TLS certificate files..."
  cp "${TLS_CERT_PEM}" "${TMP_CERT}"
  cp "${TLS_KEY_PEM}" "${TMP_KEY}"

  if [ -n "${TLS_CHAIN_PEM}" ]; then
    if [ ! -f "${TLS_CHAIN_PEM}" ]; then
      echo "TLS_CHAIN_PEM file not found."
      exit 1
    fi
    cp "${TLS_CHAIN_PEM}" "${TMP_CHAIN}"
    cat "${TMP_CERT}" "${TMP_CHAIN}" > "${TMP_FULLCHAIN}"
  else
    cp "${TMP_CERT}" "${TMP_FULLCHAIN}"
  fi
else
  if [ "${GENERATE_SELF_SIGNED}" != "true" ]; then
    echo "No TLS_CERT_PEM/TLS_KEY_PEM provided."
    echo "Provide real cert files, or set GENERATE_SELF_SIGNED=true as fallback."
    exit 1
  fi

  echo "WARNING: generating self-signed TLS certificate (not recommended for internet production)."
  SAN_VALUE="DNS:${DOMAIN}"
  if [ -n "${SAN_LIST}" ]; then
    SAN_VALUE="${SAN_VALUE},${SAN_LIST}"
  fi

  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days "${CERT_DAYS}" \
    -subj "/CN=${DOMAIN}" \
    -addext "subjectAltName=${SAN_VALUE}" \
    -keyout "${TMP_KEY}" \
    -out "${TMP_CERT}"
  cp "${TMP_CERT}" "${TMP_FULLCHAIN}"
fi

echo "Building PFX for server runtime..."
openssl pkcs12 -export \
  -inkey "${TMP_KEY}" \
  -in "${TMP_FULLCHAIN}" \
  -out "${CERT_DIR}/server.pfx" \
  -passout "pass:${CERT_PASSWORD}"

cp "${TMP_CERT}" "${CERT_DIR}/tls.crt"
cp "${TMP_KEY}" "${CERT_DIR}/tls.key"

# 3) Build production image
echo "Building image: ${IMAGE_REF}"
docker build -t "${IMAGE_REF}" "${ROOT_DIR}"

# 4) Export artifacts
if [ "${EXPORT_IMAGE_TAR}" = "true" ]; then
  TAR_NAME="${IMAGE_NAME//\//_}_${IMAGE_TAG}.tar"
  echo "Exporting image tar: ${OUTPUT_DIR}/${TAR_NAME}"
  docker save "${IMAGE_REF}" -o "${OUTPUT_DIR}/${TAR_NAME}"
fi

cat > "${OUTPUT_DIR}/prod.env" <<EOF
REQUIRE_WSS=true
TLS_CERT_PATH=/app/certs/server.pfx
TLS_CERT_PASSWORD=${CERT_PASSWORD}
SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64
MAX_CONNECTIONS=1000
MAX_MESSAGE_BYTES=65536
RATE_LIMIT_COUNT=30
RATE_LIMIT_WINDOW_SECONDS=10
IP_BLOCK_SECONDS=120
CHALLENGE_TTL_SECONDS=120
MAX_CLOCK_SKEW_SECONDS=60
REPLAY_WINDOW_SECONDS=120
EOF

cat > "${OUTPUT_DIR}/run_prod_example.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

# Example production run command:
# 1) copy deploy/certs + deploy/keys to deployment host
# 2) import image tar: docker load -i onlinemsgserver_prod.tar
# 3) run with env file:
#
# docker run -d --name onlinemsgserver --restart unless-stopped \
#   -p 13173:13173 \
#   --env-file /path/prod.env \
#   -v /path/certs:/app/certs:ro \
#   -v /path/keys:/app/keys:ro \
#   onlinemsgserver:prod
EOF
chmod +x "${OUTPUT_DIR}/run_prod_example.sh"

echo
echo "Done."
echo "Artifacts:"
echo "- TLS runtime cert: ${CERT_DIR}/server.pfx"
echo "- TLS PEM cert/key: ${CERT_DIR}/tls.crt , ${CERT_DIR}/tls.key"
echo "- Protocol RSA key: ${KEY_DIR}/server_rsa_pkcs8.b64"
echo "- Deployment env: ${OUTPUT_DIR}/prod.env"
if [ "${EXPORT_IMAGE_TAR}" = "true" ]; then
  echo "- Image tar: ${OUTPUT_DIR}/${IMAGE_NAME//\//_}_${IMAGE_TAG}.tar"
fi
