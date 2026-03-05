# OnlineMsgServer

在线消息中转服务（WebSocket + RSA），支持客户端鉴权、单播转发、广播、签名校验、防重放与限流。

## 仓库结构

- `deploy/`：一键部署与生产产物脚本
- `web-client/`：React Web 客户端
- `android-client/`：Android（Kotlin + Compose）客户端

## 运行前提

- `.NET 8 SDK`
- `Docker`
- `openssl`
- 部署脚本 `deploy/deploy_test_ws.sh` 与 `deploy/redeploy_with_lan_cert.sh` 依赖 `ipconfig`、`route`（当前按 macOS 环境编写）

## 快速开始

先进入仓库根目录：

```bash
cd <repo-root>
```

### 1) 测试模式（WS）

```bash
bash deploy/deploy_test_ws.sh
```

脚本会自动生成/复用协议私钥、构建镜像并以 `REQUIRE_WSS=false` 启动容器。

### 2) 安全模式（WSS + 局域网证书）

```bash
bash deploy/redeploy_with_lan_cert.sh
```

脚本会重签包含当前局域网 IP 的证书、构建镜像并以 `REQUIRE_WSS=true` 启动容器。

### 3) 生产准备（证书 + 镜像 + 部署产物）

```bash
DOMAIN=chat.example.com \
TLS_CERT_PEM=/path/fullchain.pem \
TLS_KEY_PEM=/path/privkey.pem \
TLS_CHAIN_PEM=/path/chain.pem \
CERT_PASSWORD='change-me' \
bash deploy/prepare_prod_release.sh
```

输出目录默认在 `deploy/output/prod`，包含 `prod.env`、镜像 tar（可选）和运行示例脚本。

无 CA 证书时可临时使用自签名（仅测试）：

```bash
DOMAIN=chat.example.com \
SAN_LIST='DNS:www.chat.example.com,IP:10.0.0.8' \
GENERATE_SELF_SIGNED=true \
CERT_PASSWORD='change-me' \
bash deploy/prepare_prod_release.sh
```

## 手动 Docker 启动示例

### WS（测试）

```bash
docker run -d --name onlinemsgserver --restart unless-stopped \
  -p 13173:13173 \
  -v "$(pwd)/deploy/keys:/app/keys:ro" \
  -e REQUIRE_WSS=false \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  onlinemsgserver:latest
```

### WSS（生产/预生产）

```bash
docker run -d --name onlinemsgserver --restart unless-stopped \
  -p 13173:13173 \
  -v "$(pwd)/deploy/certs:/app/certs:ro" \
  -v "$(pwd)/deploy/keys:/app/keys:ro" \
  -e REQUIRE_WSS=true \
  -e TLS_CERT_PATH=/app/certs/server.pfx \
  -e TLS_CERT_PASSWORD=changeit \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  onlinemsgserver:latest
```

## 协议说明

### 加密方式

- RSA-2048-OAEP-SHA256
- 明文按 190 字节分块加密
- 密文按 256 字节分块解密
- 业务消息传输为 base64 字符串

### 通用包结构（客户端 -> 服务端）

```json
{
  "type": "publickey|forward|broadcast",
  "key": "",
  "data": {}
}
```

### 连接首包（服务端 -> 客户端，明文）

```json
{
  "type": "publickey",
  "data": {
    "publicKey": "服务端公钥(base64 SPKI)",
    "authChallenge": "一次性挑战值",
    "authTtlSeconds": 120,
    "certFingerprintSha256": "TLS证书指纹(启用WSS时)"
  }
}
```

### 鉴权登记 `type=publickey`（客户端 -> 服务端）

- `key`：用户名（为空时服务端会生成匿名名）
- `data`：

```json
{
  "publicKey": "客户端公钥(base64 SPKI)",
  "challenge": "上一步 authChallenge",
  "timestamp": 1739600000,
  "nonce": "随机字符串",
  "signature": "签名(base64)"
}
```

签名串：

```text
publickey\n{userName}\n{publicKey}\n{challenge}\n{timestamp}\n{nonce}
```

### 单播 `type=forward`

- `key`：目标客户端公钥
- `data`：

```json
{
  "payload": "消息内容",
  "timestamp": 1739600000,
  "nonce": "随机字符串",
  "signature": "签名(base64)"
}
```

签名串：

```text
forward\n{targetPublicKey}\n{payload}\n{timestamp}\n{nonce}
```

### 广播 `type=broadcast`

- `key`：可为空字符串
- `data`：同 `forward`

签名串：

```text
broadcast\n{key}\n{payload}\n{timestamp}\n{nonce}
```

### 连接流程

1. 客户端建立 WebSocket 连接后接收明文 `publickey` 首包。
2. 客户端发送签名鉴权包（`type=publickey`）。
3. 鉴权成功后，客户端发送 `forward` / `broadcast` 业务消息（加密 + 签名）。

## 环境变量

- `LISTEN_PORT`：监听端口，默认 `13173`
- `REQUIRE_WSS`：是否启用 WSS，默认 `false`
- `TLS_CERT_PATH`：证书路径（启用 WSS 时必填）
- `TLS_CERT_PASSWORD`：证书密码（可空）
- `SERVER_PRIVATE_KEY_B64`：服务端私钥（PKCS8 base64）
- `SERVER_PRIVATE_KEY_PATH`：服务端私钥文件路径（与上面二选一）
- `ALLOW_EPHEMERAL_SERVER_KEY`：允许使用临时内存私钥，默认 `false`
- `MAX_CONNECTIONS`：最大连接数，默认 `1000`
- `MAX_MESSAGE_BYTES`：单消息最大字节数，默认 `65536`
- `RATE_LIMIT_COUNT`：限流窗口允许消息数，默认 `30`
- `RATE_LIMIT_WINDOW_SECONDS`：限流窗口秒数，默认 `10`
- `IP_BLOCK_SECONDS`：触发滥用后的封禁秒数，默认 `120`
- `CHALLENGE_TTL_SECONDS`：挑战值有效期秒数，默认 `120`
- `MAX_CLOCK_SKEW_SECONDS`：允许时钟偏差秒数，默认 `60`
- `REPLAY_WINDOW_SECONDS`：防重放窗口秒数，默认 `120`

## 客户端文档

- Web 客户端说明：`web-client/README.md`
- Android 客户端说明：`android-client/README.md`
