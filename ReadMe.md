# OnlineMsgServer

一个基于 WebSocket 的在线消息中转服务，使用 RSA 完成握手、公钥鉴权和业务包加密。

当前版本除了单机广播/私聊，还支持“服务器伪装成普通用户”的 peer 互联模式：

- 客户端外层协议不变
- 服务器之间通过普通 `publickey / forward / broadcast` 连接
- 本地私聊未命中时，服务端可继续向 peer 盲转发
- 广播可在 peer 节点之间扩散
- 服务端内置短期 `seen-cache`，按 `hash(sender + type + key + payload)` 去重

这套 peer 能力更接近“盲转发网络”，不是强一致的用户目录或联邦路由系统。

## 功能概览

- WebSocket 服务，支持 `ws://` 和 `wss://`
- 明文首包下发服务端公钥与一次性 challenge
- 客户端使用自己的 RSA 公钥 + 签名完成鉴权
- 业务消息支持广播和按公钥私聊
- 签名校验、防重放、限流、IP 封禁、消息大小限制
- 可选 peer 网络：广播扩散、私聊 miss 后继续中继
- Android / Web 客户端可直接复用现有协议

## 仓库结构

- `Common/`：协议消息与业务处理器
- `Core/`：安全配置、用户会话、peer 网络、RSA 服务
- `deploy/`：本地测试 / 局域网证书 / 生产准备脚本
- `web-client/`：React Web 客户端
- `android-client/`：Android 客户端

## 运行依赖

- `.NET 8 SDK`
- `Docker`
- `openssl`

本仓库附带的 `deploy/*.sh` 脚本按 macOS 环境编写，依赖：

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

脚本会：

- 生成或复用协议私钥 `deploy/keys/server_rsa_pkcs8.b64`
- 构建 Docker 镜像
- 以 `REQUIRE_WSS=false` 启动单节点服务

### 2. 局域网测试：WSS

```bash
bash deploy/redeploy_with_lan_cert.sh
```

脚本会：

- 自动探测当前局域网 IP
- 生成包含 LAN IP 的自签名证书
- 生成运行时使用的 `server.pfx`
- 构建镜像并以 `REQUIRE_WSS=true` 启动容器

适合 Android 真机、同网段设备和浏览器本地联调。

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
- 运行时证书与协议私钥

如果只是临时测试，也可以生成自签名证书：

```bash
DOMAIN=chat.example.com \
SAN_LIST='DNS:www.chat.example.com,IP:10.0.0.8' \
GENERATE_SELF_SIGNED=true \
CERT_PASSWORD='change-me' \
bash deploy/prepare_prod_release.sh
```

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

下面这个例子会启动第二个节点，对外提供 `13174`，并主动连到第一节点：

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

这里有一个很重要的约束：

- 如果客户端访问的是 `wss://host:13174/`
- 那容器内 `LISTEN_PORT` 也应当是 `13174`

`WebSocketSharp` 会校验握手请求里的 `Host: host:port`，容器内监听端口和客户端看到的端口不一致时，可能直接返回 `400 Bad Request`。

## 协议说明

### 加密方式

- 服务端握手公钥：RSA-2048（SPKI / PKCS8）
- 传输加密：`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`
- 明文按 `190` 字节分块加密
- 密文按 `256` 字节分块解密
- WebSocket 上传输的是 base64 字符串

### 通用包结构

客户端发给服务端的明文结构如下，随后再整体用服务端公钥加密：

```json
{
  "type": "publickey|forward|broadcast",
  "key": "",
  "data": {}
}
```

### 首包：服务端 -> 客户端（明文）

客户端建立连接后，服务端立即发送：

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

### 鉴权：`type=publickey`

- `key`：用户名
- `data.publicKey`：客户端公钥
- `data.challenge`：首包中的 `authChallenge`
- `data.timestamp`：Unix 秒级时间戳
- `data.nonce`：随机串
- `data.signature`：客户端私钥签名

示例：

```json
{
  "type": "publickey",
  "key": "guest-123456",
  "data": {
    "publicKey": "base64-spki",
    "challenge": "challenge-from-server",
    "timestamp": 1739600000,
    "nonce": "random-string",
    "signature": "base64-signature"
  }
}
```

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

```json
{
  "type": "forward",
  "key": "target-user-public-key",
  "data": {
    "payload": "hello",
    "timestamp": 1739600000,
    "nonce": "random-string",
    "signature": "base64-signature"
  }
}
```

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

### 连接流程

1. 客户端建立 WebSocket 连接。
2. 服务端发送明文 `publickey` 首包。
3. 客户端用自己的私钥签名后发送 `type=publickey` 鉴权包。
4. 服务端返回加密的 `auth_ok`。
5. 客户端开始发送 `forward` / `broadcast`。

## Peer 网络说明

Peer 网络不引入新的客户端外层协议。节点之间也是普通登录用户，只是服务端会把这类会话当成 peer 处理。

当前行为：

- 本地广播：先发给本地普通客户端，再扩散到 peer
- 从 peer 收到广播：投递给本地普通客户端，再继续扩散
- 本地私聊命中：直接投递
- 本地私聊 miss：包装为内部 relay 后继续发给 peer
- peer 收到私聊 relay：本地命中就投递，命不中就继续向其他 peer 转发

当前实现特点：

- 不做用户发现
- 不维护“谁在哪台服务器”的路由表
- 只保证尽力转发
- 依赖短期 `seen-cache` 防止消息在环路里重复扩散

### Peer 命名

为了让客户端界面更像普通聊天用户：

- 服务端内部仍用 `peer:` 前缀区分 peer 会话
- 发给客户端前会去掉这个内部前缀
- 如果显式设置了 `PEER_NODE_NAME=peer-node-b`，客户端看到的是 `peer-node-b`
- 如果没有显式设置 `PEER_NODE_NAME`，默认自动生成 `guest-xxxxxx`

## 环境变量

### 基础运行

- `LISTEN_PORT`：监听端口，默认 `13173`
- `REQUIRE_WSS`：是否启用 WSS，默认 `false`
- `TLS_CERT_PATH`：PFX 证书路径，启用 WSS 时必填
- `TLS_CERT_PASSWORD`：PFX 证书密码，可空

### 协议私钥

- `SERVER_PRIVATE_KEY_B64`：协议私钥（PKCS8 base64）
- `SERVER_PRIVATE_KEY_PATH`：协议私钥文件路径
- `ALLOW_EPHEMERAL_SERVER_KEY`：若未提供私钥，是否允许启动临时内存私钥，默认 `false`

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
- `PEER_URLS`：要主动连接的 peer 地址，逗号分隔
- `PEER_RECONNECT_SECONDS`：peer 断线后的重连间隔，默认 `5`

## 本地调试建议

### Android 连 `ws://`

Android 9 之后默认禁止明文流量。若用 `ws://` 调试，需要客户端显式允许 cleartext。

### Android 连 `wss://`

若服务端使用自签名证书，需要满足其一：

- 设备/模拟器信任这张 CA
- Android debug 包内置该 CA 的信任配置

### 多实例本地测试

同一台机器上起多个节点时，建议：

- 为每个节点分配不同 `LISTEN_PORT`
- 对外映射端口和 `LISTEN_PORT` 保持一致
- 第一个节点使用固定协议私钥
- 第二个测试节点可使用 `ALLOW_EPHEMERAL_SERVER_KEY=true`

## 排错

### `expected HTTP 101 but was 400`

常见原因：

- 容器内 `LISTEN_PORT` 与客户端访问端口不一致
- 客户端实际访问了错误的 `Host: port`

### Android 显示“未收到服务器首包”

当前服务端已禁用 WebSocket 压缩扩展协商，以避免某些 Android/OkHttp 路径拿不到压缩后的首个 `publickey` Hello。

### Peer 连不上 WSS

当前 peer 出站连接使用 .NET `ClientWebSocket`，可以直连 `wss://` peer。若是自签名测试环境，请确认目标地址可达，并尽量使用稳定的局域网地址或 `host.docker.internal`。

## 相关文档

- Web 客户端：[web-client/README.md](/Users/solux/Codes/OnlineMsgServer/web-client/README.md)
- Android 客户端：[android-client/README.md](/Users/solux/Codes/OnlineMsgServer/android-client/README.md)
