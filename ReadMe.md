# OnlineMsgServer

## 项目介绍
Online Message Server 在线消息服务器，使用非对称加密确保安全发送消息

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
    docker run -d --rm -p 13173:13173 onlinemsgserver
    ```

4. 使用客户端连接服务器进行通信

## 通信规则

### 消息加密方式
使用RSA-2048-OAEP-256非对称加密，消息需分块（块大小190字节）进行加密，按顺序合并后编码成base64字符串进行发送。

收到密文后需从base64解码，然后按照块大小256字节进行分块解密，按顺序合并后得到原文。

使用服务器公钥加密消息发送给服务器，然后使用自己的私钥解密从服务器收到的消息。

### Message结构
```json
{
  "publicKey": "公钥（X.509 SubjectPublicKeyInfo格式转base64字符串）",
  "from": "是谁发送的消息，公钥（base64字符串格式），可能为空",
  "to": "指示服务器要发送data的对象公钥（base64字符串格式），留空代表广播给所有在线客户端",
  "data": "base64编码的字符串，需要服务器广播或转发的数据，服务器不对其做任何处理"
}
```

### 交互过程
1. 客户端第一次和服务器建立连接时，服务器即会返回一个**没有加密**的publickey消息
   ```json
    {
        "publicKey": "XNoZGtsamFoc2xrZGpoYXNrbGQ=..."
    }
    ```
2. 客户端将自己的公钥加密发送给服务器，视作登记，只有登记的客户端才可以收到服务器消息

3. 登记成功会收到回复，客户端需要使用自己私钥解密来获取内容
   
4. 客户端发送消息指示服务器是转发data中的内容还是广播data中的内容


