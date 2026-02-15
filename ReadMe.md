# OnlineMsgServer

在线消息中转服务（WebSocket + RSA），支持客户端鉴权、单播转发、广播、签名校验、防重放、限流。

## 当前默认行为

- 服务端默认 `REQUIRE_WSS=false`（便于内外网测试）
- 仍支持 `WSS(TLS)`，可通过环境变量启用
- 客户端需要完成 challenge-response 鉴权后才能发业务消息

## 快速开始

### 1) 测试模式（推荐先跑通）

```bash
cd /Users/<user>/Codes/OnlineMsgServer
bash deploy/deploy_test_ws.sh
```

这个脚本会自动：
- 生成/复用服务端 RSA 私钥（`deploy/keys`）
- 构建镜像并重启容器
- 以 `REQUIRE_WSS=false` 启动服务
- 输出可直接使用的 `ws://` 地址

### 2) 安全模式（WSS + 局域网证书）

```bash
cd /Users/<user>/Codes/OnlineMsgServer
bash deploy/redeploy_with_lan_cert.sh
```

这个脚本会自动：
- 重新生成带当前 LAN IP 的 TLS 证书（`deploy/certs`）
- 构建镜像并重启容器
- 以 `REQUIRE_WSS=true` 启动服务
- 输出可直接使用的 `wss://` 地址

### 3) 生产准备（证书 + 镜像 + 部署产物）

```bash
cd /Users/<user>/Codes/OnlineMsgServer
DOMAIN=chat.example.com \
TLS_CERT_PEM=/path/fullchain.pem \
TLS_KEY_PEM=/path/privkey.pem \
TLS_CHAIN_PEM=/path/chain.pem \
CERT_PASSWORD='change-me' \
bash deploy/prepare_prod_release.sh
```

脚本会自动：
- 准备服务端协议私钥（`deploy/keys`）
- 生成运行时 `server.pfx`（`deploy/certs`）
- 构建生产镜像（默认 `onlinemsgserver:prod`）
- 导出部署产物到 `deploy/output/prod`（`prod.env`、镜像 tar、运行示例脚本）

如果你暂时没有 CA 证书，也可用自签名兜底（仅测试）：

```bash
DOMAIN=chat.example.com SAN_LIST='DNS:www.chat.example.com,IP:10.0.0.8' GENERATE_SELF_SIGNED=true bash deploy/prepare_prod_release.sh
```

## 手动 Docker 启动示例

### WS（测试）

```bash
docker run -d --name onlinemsgserver --restart unless-stopped \
  -p 13173:13173 \
  -v /Users/<user>/Codes/OnlineMsgServer/deploy/keys:/app/keys:ro \
  -e REQUIRE_WSS=false \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  onlinemsgserver:latest
```

### WSS（生产/半生产）

```bash
docker run -d --name onlinemsgserver --restart unless-stopped \
  -p 13173:13173 \
  -v /Users/<user>/Codes/OnlineMsgServer/deploy/certs:/app/certs:ro \
  -v /Users/<user>/Codes/OnlineMsgServer/deploy/keys:/app/keys:ro \
  -e REQUIRE_WSS=true \
  -e TLS_CERT_PATH=/app/certs/server.pfx \
  -e TLS_CERT_PASSWORD=changeit \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  onlinemsgserver:latest
```

## 协议说明（客户端 -> 服务端）

### 加密方式

- RSA-2048-OAEP-SHA256
- 明文分块 190 字节加密
- 密文按 256 字节分块解密
- 传输格式为 base64 字符串

### 通用包结构

```json
{
  "type": "publickey|forward|broadcast",
  "key": "",
  "data": {}
}
```

### 1) 鉴权登记 `type=publickey`

- `key`：用户名（可空）
- `data`：

```json
{
  "publicKey": "客户端公钥(base64 SPKI)",
  "challenge": "服务端下发挑战值",
  "timestamp": 1739600000,
  "nonce": "随机字符串",
  "signature": "签名(base64)"
}
```

签名串：

```text
publickey\n{userName}\n{publicKey}\n{challenge}\n{timestamp}\n{nonce}
```

### 2) 单播转发 `type=forward`

- `key`：目标公钥
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

### 3) 广播 `type=broadcast`

- `key`：可空
- `data`：同 `forward`

签名串：

```text
broadcast\n{key}\n{payload}\n{timestamp}\n{nonce}
```

### 连接流程

1. 客户端连接后，服务端先返回未加密 `publickey`（含服务端公钥、challenge、TTL、证书指纹）。
2. 客户端发送签名鉴权包（`type=publickey`）。
3. 鉴权成功后发送 `forward` / `broadcast` 业务包。

## 环境变量

- `LISTEN_PORT`：监听端口（默认 `13173`）
- `REQUIRE_WSS`：是否启用 WSS（默认 `false`）
- `TLS_CERT_PATH`：证书路径（WSS 必填）
- `TLS_CERT_PASSWORD`：证书密码（可空）
- `SERVER_PRIVATE_KEY_B64`：服务端私钥（PKCS8 base64）
- `SERVER_PRIVATE_KEY_PATH`：服务端私钥文件路径（与上面二选一）
- `ALLOW_EPHEMERAL_SERVER_KEY`：允许仅内存临时私钥（默认 `false`）
- `MAX_CONNECTIONS`：最大连接数
- `MAX_MESSAGE_BYTES`：最大消息字节数
- `RATE_LIMIT_COUNT`：限流窗口内最大消息数
- `RATE_LIMIT_WINDOW_SECONDS`：限流窗口秒数
- `IP_BLOCK_SECONDS`：触发滥用后 IP 封禁秒数
- `CHALLENGE_TTL_SECONDS`：challenge 有效期
- `MAX_CLOCK_SKEW_SECONDS`：允许时钟偏差
- `REPLAY_WINDOW_SECONDS`：防重放窗口

## 前端（React）

前端目录：`/Users/<user>/Codes/OnlineMsgServer/web-client`

```bash
cd /Users/<user>/Codes/OnlineMsgServer/web-client
npm install
npm run dev
```

当前前端能力：
- 默认隐藏协议细节，手动地址放在“高级连接设置”
- 支持广播/私聊、查看并复制自己的公钥
- 每条消息支持一键复制
- 自动处理超长消息换行，不溢出消息框
- 用户名和客户端私钥本地持久化，刷新后继续使用

更多前端说明见 `web-client/README.md`。
