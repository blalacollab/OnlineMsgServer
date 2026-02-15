# OnlineMsgServer

## 项目介绍
Online Message Server 在线消息服务器，使用 RSA 非对称加密进行消息中转。

当前版本默认启用安全策略：
- 强制 `WSS(TLS)` 传输
- 客户端 challenge-response 鉴权
- 消息签名、时间戳、nonce 防重放
- 连接/速率/消息大小限制

## 使用 Docker 运行

### 步骤
1. 进入项目目录：
    ```bash
    cd OnlineMsgServer
    ```

2. 构建镜像：
    ```bash
    docker build -t onlinemsgserver .
    ```

3. 启动容器：
    ```bash
    docker run -d --rm -p 13173:13173 \
      -e TLS_CERT_PATH=/app/certs/server.pfx \
      -e TLS_CERT_PASSWORD=your_password \
      -e SERVER_PRIVATE_KEY_B64=base64_pkcs8_private_key \
      -v /your/local/certs:/app/certs:ro \
      onlinemsgserver
    ```

4. 使用客户端连接服务器进行通信

## 通信规则

### 消息加密方式
使用RSA-2048-OAEP-256非对称加密，消息需分块（块大小190字节）进行加密，按顺序合并后编码成base64字符串进行发送。

收到密文后需从base64解码，然后按照块大小256字节进行分块解密，按顺序合并后得到原文。

使用服务器公钥加密消息发送给服务器，然后使用自己的私钥解密从服务器收到的消息。

### Message结构（客户端 -> 服务端）
```json
{
  "type": "publickey|forward|broadcast",
  "key": "",
  "data": {}
}
```

#### 1) 客户端登记鉴权 `type=publickey`
`key` 为用户名（可空）。
`data` 结构：
```json
{
  "publicKey": "客户端公钥(base64 SubjectPublicKeyInfo)",
  "challenge": "服务端下发的一次性挑战值",
  "timestamp": 1739600000,
  "nonce": "随机字符串",
  "signature": "客户端对签名串的签名(base64)"
}
```
签名串：
```text
publickey\n{userName}\n{publicKey}\n{challenge}\n{timestamp}\n{nonce}
```

#### 2) 单播转发 `type=forward`
`key` 为目标用户公钥。`data` 结构：
```json
{
  "payload": "业务消息",
  "timestamp": 1739600000,
  "nonce": "随机字符串",
  "signature": "签名(base64)"
}
```
签名串：
```text
forward\n{targetPublicKey}\n{payload}\n{timestamp}\n{nonce}
```

#### 3) 广播 `type=broadcast`
`key` 可空，`data` 与 `forward` 相同。签名串：
```text
broadcast\n{key}\n{payload}\n{timestamp}\n{nonce}
```

### 交互过程
1. 客户端第一次和服务器建立连接时，服务器会返回一个**未加密**的 `publickey` 消息，包含：
   - `publicKey`: 服务端公钥
   - `authChallenge`: 一次性挑战值
   - `authTtlSeconds`: challenge 过期时间
   - `certFingerprintSha256`: 服务端证书 SHA-256 指纹（用于客户端 pinning）

   ```json
   {
     "type": "publickey",
     "data": {
       "publicKey": "....",
       "authChallenge": "....",
       "authTtlSeconds": 120,
       "certFingerprintSha256": "...."
     }
   }
   ```

2. 客户端发送 `publickey` 完成挑战签名鉴权，只有鉴权成功才可继续发送业务消息。
3. 客户端发送 `forward` 和 `broadcast`，消息需带签名/时间戳/nonce。

## 安全相关环境变量

- `REQUIRE_WSS`：是否强制 WSS（默认 `true`）
- `TLS_CERT_PATH`：TLS 证书路径（PFX）
- `TLS_CERT_PASSWORD`：TLS 证书密码（可空）
- `SERVER_PRIVATE_KEY_B64`：服务端 RSA 私钥（PKCS8 base64）
- `SERVER_PRIVATE_KEY_PATH`：服务端 RSA 私钥文件路径（二选一）
- `ALLOW_EPHEMERAL_SERVER_KEY`：是否允许仅内存临时私钥（默认 `false`）
- `MAX_CONNECTIONS`：最大连接数
- `MAX_MESSAGE_BYTES`：单条消息最大字节
- `RATE_LIMIT_COUNT`：限流窗口内最大消息数
- `RATE_LIMIT_WINDOW_SECONDS`：限流窗口秒数
- `IP_BLOCK_SECONDS`：触发滥用后的 IP 封禁秒数
- `CHALLENGE_TTL_SECONDS`：challenge 有效期
- `MAX_CLOCK_SKEW_SECONDS`：允许时钟偏移
- `REPLAY_WINDOW_SECONDS`：防重放窗口

## 测试环境一键部署（WS，不强制WSS）

```bash
cd /Users/<user>/Codes/OnlineMsgServer
bash deploy/deploy_test_ws.sh
```

脚本将自动：
- 生成/复用服务端 RSA 私钥（`deploy/keys`）
- 构建镜像并重启容器
- 以 `REQUIRE_WSS=false` 启动服务
- 输出可直接用于前端的 `ws://` 地址

## React 前端

项目内置 React 前端目录：`/Users/<user>/Codes/OnlineMsgServer/web-client`

```bash
cd web-client
npm install
npm run dev
```

前端界面默认隐藏协议细节，支持在“高级连接设置”里手动指定服务器地址。
