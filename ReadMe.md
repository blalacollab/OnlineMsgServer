# OnlineMsgServer

一个仅保留服务端代码的 WebSocket 在线消息中转服务，使用 RSA 完成握手、公钥鉴权和业务包加密。

当前实现包含两层能力：

- 单节点消息中转：广播、按公钥私聊、鉴权、签名校验、防重放、限流
- 可选 peer 中继：服务器作为“普通用户”互连，在不改客户端外层协议的前提下扩散广播，并在私聊 miss 时继续盲转发

这套 peer 机制是“尽力转发”的 overlay 网络，不做全网用户发现，也不维护全局路由目录。

## 仓库结构

- `Common/`：消息模型、消息处理器、协议解析
- `Core/`：RSA、安全配置、用户会话、peer 网络
- `deploy/`：本地测试、局域网证书、生产准备脚本
- `Program.cs`：服务入口
- `OnlineMsgServer.csproj`：服务端项目文件

## 运行依赖

- `.NET 8 SDK`
- `Docker`
- `openssl`

附带的 `deploy/*.sh` 当前按 macOS 环境编写，依赖：

- `ipconfig`
- `route`
- `awk`
- `base64`
- `tr`

## 快速开始

先进入仓库根目录：

```bash
cd <repo-root>
```

### 1. 本地测试：WS

```bash
bash deploy/deploy_test_ws.sh
```

这个脚本会：

- 生成或复用协议私钥 `deploy/keys/server_rsa_pkcs8.b64`
- 构建 Docker 镜像
- 以 `REQUIRE_WSS=false` 启动服务

### 2. 局域网测试：WSS

```bash
bash deploy/redeploy_with_lan_cert.sh
```

这个脚本会：

- 自动探测当前局域网 IP
- 生成包含 LAN IP 的自签名证书
- 生成运行时用的 `server.pfx`
- 构建镜像并以 `REQUIRE_WSS=true` 启动服务

### 3. 生产准备

```bash
DOMAIN=chat.example.com \
TLS_CERT_PEM=/path/fullchain.pem \
TLS_KEY_PEM=/path/privkey.pem \
TLS_CHAIN_PEM=/path/chain.pem \
CERT_PASSWORD='change-me' \
bash deploy/prepare_prod_release.sh
```

输出默认在 `deploy/output/prod/`，包括：

- `prod.env`
- Docker 镜像 tar（可选）
- 运行示例脚本
- 运行时证书和协议私钥

## 手动 Docker 启动

### 单节点：WS

```bash
docker run -d --name onlinemsgserver --restart unless-stopped \
  -p 13173:13173 \
  -v "$(pwd)/deploy/keys:/app/keys:ro" \
  -e REQUIRE_WSS=false \
  -e SERVER_PRIVATE_KEY_PATH=/app/keys/server_rsa_pkcs8.b64 \
  onlinemsgserver:latest
```

### 单节点：WSS

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

### 第二节点：通过 peer 连到第一节点

```bash
docker run -d --name onlinemsgserver-peer2 --restart unless-stopped \
  -p 13174:13174 \
  -v "$(pwd)/deploy/certs:/app/certs:ro" \
  -e REQUIRE_WSS=true \
  -e LISTEN_PORT=13174 \
  -e TLS_CERT_PATH=/app/certs/server.pfx \
  -e TLS_CERT_PASSWORD=changeit \
  -e ALLOW_EPHEMERAL_SERVER_KEY=true \
  -e PEER_NODE_NAME=peer-node-b \
  -e PEER_URLS=wss://host.docker.internal:13173/ \
  onlinemsgserver:latest
```

注意：

- 客户端访问的端口和容器内 `LISTEN_PORT` 最好保持一致
- `WebSocketSharp` 会校验握手里的 `Host: host:port`
- 如果对外访问端口和服务实际监听端口不一致，可能直接返回 `400 Bad Request`

## 协议概览

### 加密方式

- 服务端握手公钥：RSA-2048（SPKI / PKCS8）
- 传输加密：`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`
- 明文按 `190` 字节分块加密
- 密文按 `256` 字节分块解密
- WebSocket 上传输内容为 base64 字符串

### 通用包结构

客户端发给服务端的明文结构如下，随后整体用服务端公钥加密：

```json
{
  "type": "publickey|forward|broadcast|rename",
  "key": "",
  "data": {}
}
```

### 首包：服务端 -> 客户端（明文）

```json
{
  "type": "publickey",
  "data": {
    "publicKey": "服务端公钥(base64 SPKI)",
    "authChallenge": "一次性 challenge",
    "authTtlSeconds": 120,
    "certFingerprintSha256": "TLS证书指纹(启用WSS时)"
  }
}
```

### 鉴权：`type=publickey`

- `key`：用户名
- `data.publicKey`：客户端公钥
- `data.challenge`：首包中的 `authChallenge`
- `data.timestamp`：Unix 秒级时间戳
- `data.nonce`：随机串
- `data.signature`：客户端私钥签名

签名原文：

```text
publickey
{userName}
{publicKey}
{challenge}
{timestamp}
{nonce}
```

### 私聊：`type=forward`

- `key`：目标用户公钥
- `data.payload`：消息内容
- `data.timestamp` / `data.nonce` / `data.signature`：发送者签名信息

签名原文：

```text
forward
{targetPublicKey}
{payload}
{timestamp}
{nonce}
```

### 广播：`type=broadcast`

- `key`：通常为空字符串
- `data`：结构与 `forward` 相同

签名原文：

```text
broadcast
{key}
{payload}
{timestamp}
{nonce}
```

### 改名：`type=rename`

- `key`：保留为空字符串
- `data.payload`：新的显示名
- `data.timestamp` / `data.nonce` / `data.signature`：发送者签名信息

签名原文：

```text
rename
{key}
{newDisplayName}
{timestamp}
{nonce}
```

成功后服务端会回：

- `rename_ok`
- 或 `rename_error`

普通用户不能把自己的名字改成保留的 peer 前缀。

## Peer 网络说明

peer 网络不引入新的客户端外层协议。节点之间也是普通登录用户，只是服务端会把它们识别为 peer 会话并走不同的转发逻辑。

当前行为：

- 本地广播：发给本地普通客户端，再扩散给 peer
- 从 peer 收到广播：投递给本地普通客户端，再继续扩散
- 本地私聊命中：直接投递
- 本地私聊 miss：包装成内部 relay 后继续发给 peer
- peer 收到 relay：本地命中就投递，命不中就继续向其他 peer 转发

当前实现特点：

- 不做用户发现
- 不维护全网路由表
- 只做尽力转发
- 依赖短期 `seen-cache` 防环路和重复扩散

## 环境变量

### 基础运行

- `LISTEN_PORT`：监听端口，默认 `13173`
- `REQUIRE_WSS`：是否启用 WSS，默认 `false`
- `TLS_CERT_PATH`：PFX 证书路径，启用 WSS 时必填
- `TLS_CERT_PASSWORD`：PFX 证书密码，可空

### 协议私钥

- `SERVER_PRIVATE_KEY_B64`：协议私钥（PKCS8 base64）
- `SERVER_PRIVATE_KEY_PATH`：协议私钥文件路径
- `ALLOW_EPHEMERAL_SERVER_KEY`：未提供私钥时是否允许启动临时内存私钥，默认 `false`

### 安全限制

- `MAX_CONNECTIONS`：最大连接数，默认 `1000`
- `MAX_MESSAGE_BYTES`：单消息最大字节数，默认 `65536`
- `RATE_LIMIT_COUNT`：限流窗口允许消息数，默认 `30`
- `RATE_LIMIT_WINDOW_SECONDS`：限流窗口秒数，默认 `10`
- `IP_BLOCK_SECONDS`：触发滥用后的封禁秒数，默认 `120`
- `CHALLENGE_TTL_SECONDS`：challenge 有效期秒数，默认 `120`
- `MAX_CLOCK_SKEW_SECONDS`：允许时钟偏差秒数，默认 `60`
- `REPLAY_WINDOW_SECONDS`：防重放窗口秒数，默认 `120`
- `SEEN_CACHE_SECONDS`：短期去重缓存秒数，默认 `120`

### Peer

- `PEER_NODE_NAME`：peer 登录名；未显式配置时自动生成 `guest-xxxxxx`
- `PEER_USER_PREFIX`：内部保留前缀，默认 `peer:`
- `PEER_URLS`：主动连接的 peer 地址，逗号分隔
- `PEER_RECONNECT_SECONDS`：peer 断线重连间隔，默认 `5`

## 常见问题

### `expected HTTP 101 but was 400`

优先检查：

- 对外访问端口是否和 `LISTEN_PORT` 一致
- 代理或 Docker 端口映射后，握手里的 `Host: port` 是否仍然正确

### Android / 某些客户端显示“未收到服务器首包”

当前服务端已禁用 WebSocket 压缩扩展协商，避免某些 Android/OkHttp 路径拿不到压缩后的首个 `publickey` Hello。

### peer 连不上 WSS

当前 peer 出站连接使用 .NET `ClientWebSocket`，可直接连 `wss://` peer。若是自签名测试环境，请确认目标地址可达，并尽量使用稳定的局域网地址或 `host.docker.internal`。
