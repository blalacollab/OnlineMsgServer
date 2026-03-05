# OnlineMsg Android Client (Kotlin + Compose)

本目录是针对当前 `OnlineMsgServer` 协议实现的 Android 客户端。

## 已实现能力

- Kotlin + Jetpack Compose + Material3
- 与当前服务端协议兼容：
  - 首包 `publickey` 握手（明文）
  - `publickey` challenge-response 鉴权（签名）
  - `broadcast` / `forward` 消息发送（签名 + 防重放字段）
  - 消息体 RSA-OAEP-SHA256 分块加解密（190/256）
- Android Keystore 生成并持久化客户端 RSA 密钥
- 状态管理：`ViewModel + StateFlow`
- 本地偏好：`DataStore`（用户名、服务器地址、模式、系统消息开关）
- 易用性：
  - 广播/私聊一键切换
  - 消息复制
  - 我的公钥查看与复制
  - 服务器地址保存/删除
  - 状态提示与诊断信息

## 工程结构

- `app/src/main/java/com/onlinemsg/client/ui`：UI、ViewModel、状态模型
- `app/src/main/java/com/onlinemsg/client/data/crypto`：RSA 加密、签名、nonce
- `app/src/main/java/com/onlinemsg/client/data/network`：WebSocket 封装
- `app/src/main/java/com/onlinemsg/client/data/preferences`：DataStore 与地址格式化
- `app/src/main/java/com/onlinemsg/client/data/protocol`：协议 DTO

## 运行方式

1. 使用 Android Studio 打开 `android-client` 目录。
2. 等待 Gradle Sync 完成。
3. 运行 `app`。

## 联调建议

- 模拟器建议地址：`ws://10.0.2.2:13173/`
- 真机建议地址：`ws://<你的局域网IP>:13173/`
- 若服务端启用 WSS，需要 Android 设备信任对应证书。

## 协议注意事项

- 鉴权签名串：
  - `publickey\n{userName}\n{publicKey}\n{challenge}\n{timestamp}\n{nonce}`
- 业务签名串：
  - `broadcast|forward\n{key}\n{payload}\n{timestamp}\n{nonce}`
- `forward` 的 `key` 必须是目标公钥。
- `broadcast` 的 `key` 为空字符串。

## 已知限制

- 当前未内置证书固定（pinning）；如用于公网生产，建议额外启用证书固定策略。
