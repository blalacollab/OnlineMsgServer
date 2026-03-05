# OnlineMsg Web Client

React 前端客户端，适配当前仓库消息协议，默认隐藏协议细节并聚焦聊天交互。

## 开发运行

```bash
cd web-client
npm install
npm run dev
```

默认地址：`http://localhost:5173`

## 生产构建

```bash
cd web-client
npm install
npm run build
npm run preview
```

构建产物输出目录：`web-client/dist`

## 使用说明

1. 打开页面后点击“连接”。
2. 默认服务器地址会根据当前页面协议和主机自动推断：
   - 当页面是 `https` 且主机不是本机地址时：`wss://<host>/msgws/`
   - 其他情况：`ws://<host>:13173/`
3. 若首连失败且当前地址是 `ws://`，客户端会自动切换到 `wss://` 重试 1 次。
4. 如需手动指定服务器地址，在“高级连接设置”中填写，例如：
   - `wss://example.com/msgws/`
   - `ws://127.0.0.1:13173/`（本地调试）
5. “目标公钥”为空时发送广播，填写后发送私聊（`forward`）。
6. 用户名、服务器地址历史、客户端私钥会保存在浏览器本地存储中。

## 移动端注意事项

- 客户端已支持两套加密实现：
  - 优先 `WebCrypto`（性能更好）
  - 退化到纯 JS `node-forge`（适配部分 `http` 局域网场景）
- 在纯 JS 加密模式下，首次连接可能需要几秒生成密钥；客户端会复用本地缓存密钥以减少后续等待。
- 若设备浏览器过旧，仍可能无法完成加密初始化，此时会在页面提示具体原因。
- 生产环境仍建议使用 `https/wss`。
