import { useEffect, useMemo, useRef, useState } from "react";
import {
  canInitializeCrypto,
  createNonce,
  generateClientIdentity,
  rsaDecryptChunked,
  rsaEncryptChunked,
  signText,
  unixSecondsNow
} from "./crypto";

const STATUS_TEXT = {
  idle: "未连接",
  connecting: "连接中",
  handshaking: "握手中",
  authenticating: "认证中",
  ready: "已连接",
  error: "异常断开"
};
const STORAGE_DISPLAY_NAME_KEY = "oms_display_name";

function getDefaultServerUrl() {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const host = window.location.hostname || "localhost";
  return `${protocol}://${host}:13173/`;
}

function normalizeServerUrl(input) {
  let value = input.trim();
  if (!value) return "";

  if (!/^[a-z]+:\/\//i.test(value)) {
    const preferred = window.location.protocol === "https:" ? "wss" : "ws";
    value = `${preferred}://${value}`;
  }

  if (value.startsWith("http://")) {
    value = `ws://${value.slice("http://".length)}`;
  } else if (value.startsWith("https://")) {
    value = `wss://${value.slice("https://".length)}`;
  }

  if (!value.endsWith("/")) {
    value += "/";
  }
  return value;
}

function toggleWsProtocol(urlText) {
  try {
    const url = new URL(urlText);
    if (url.protocol === "ws:") {
      url.protocol = "wss:";
      return url.toString();
    }
  } catch {
    return "";
  }
  return "";
}

function createGuestName() {
  const rand = Math.random().toString(36).slice(2, 8);
  return `guest-${rand}`;
}

function getInitialDisplayName() {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_DISPLAY_NAME_KEY)?.trim();
    if (stored) {
      return stored.slice(0, 64);
    }
  } catch {
    // ignore
  }
  return createGuestName();
}

function formatTime(ts) {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  }).format(ts);
}

function safeJsonParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function summarizeKey(key = "") {
  if (!key) return "";
  if (key.length <= 16) return key;
  return `${key.slice(0, 8)}...${key.slice(-8)}`;
}

function createLocalId() {
  const c = globalThis.crypto;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  if (c?.getRandomValues) {
    const buf = new Uint8Array(12);
    c.getRandomValues(buf);
    return Array.from(buf)
      .map((v) => v.toString(16).padStart(2, "0"))
      .join("");
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function getCryptoIssueMessage() {
  if (!canInitializeCrypto()) {
    return "当前浏览器不支持必要的加密能力，请升级浏览器后重试。";
  }
  return "";
}

export default function App() {
  const wsRef = useRef(null);
  const identityRef = useRef(null);
  const identityPromiseRef = useRef(null);
  const serverPublicKeyRef = useRef("");
  const manualCloseRef = useRef(false);
  const fallbackTriedRef = useRef(false);
  const statusRef = useRef("idle");
  const authTimeoutRef = useRef(0);
  const copyNoticeTimerRef = useRef(0);
  const messageCopyTimerRef = useRef(0);
  const draftComposingRef = useRef(false);
  const targetComposingRef = useRef(false);

  const [status, setStatus] = useState("idle");
  const [statusHint, setStatusHint] = useState("点击连接开始聊天");
  const [serverUrl, setServerUrl] = useState(getDefaultServerUrl());
  const [displayName, setDisplayName] = useState(getInitialDisplayName);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [targetKey, setTargetKey] = useState("");
  const [draft, setDraft] = useState("");
  const [messages, setMessages] = useState([]);
  const [sending, setSending] = useState(false);
  const [certFingerprint, setCertFingerprint] = useState("");
  const [myPublicKey, setMyPublicKey] = useState("");
  const [publicKeyBusy, setPublicKeyBusy] = useState(false);
  const [copyNotice, setCopyNotice] = useState("");
  const [copiedMessageId, setCopiedMessageId] = useState("");

  const isConnected = status === "ready";
  const canConnect = status === "idle" || status === "error";
  const canSend = isConnected && draft.trim().length > 0 && !sending;

  const statusClass = useMemo(() => {
    if (status === "ready") return "ok";
    if (status === "error") return "bad";
    return "loading";
  }, [status]);

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  useEffect(() => {
    try {
      const value = displayName.trim();
      if (value) {
        globalThis.localStorage?.setItem(STORAGE_DISPLAY_NAME_KEY, value);
      } else {
        globalThis.localStorage?.removeItem(STORAGE_DISPLAY_NAME_KEY);
      }
    } catch {
      // ignore storage failures in private mode
    }
  }, [displayName]);

  useEffect(() => {
    return () => {
      manualCloseRef.current = true;
      if (authTimeoutRef.current) {
        clearTimeout(authTimeoutRef.current);
        authTimeoutRef.current = 0;
      }
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
      if (copyNoticeTimerRef.current) {
        clearTimeout(copyNoticeTimerRef.current);
        copyNoticeTimerRef.current = 0;
      }
      if (messageCopyTimerRef.current) {
        clearTimeout(messageCopyTimerRef.current);
        messageCopyTimerRef.current = 0;
      }
    };
  }, []);

  function pushSystem(text) {
    setMessages((prev) => [
      ...prev,
      {
        id: createLocalId(),
        role: "system",
        content: text,
        ts: Date.now()
      }
    ]);
  }

  function pushIncoming(sender, text, subtitle = "") {
    setMessages((prev) => [
      ...prev,
      {
        id: createLocalId(),
        role: "incoming",
        sender,
        subtitle,
        content: text,
        ts: Date.now()
      }
    ]);
  }

  function pushOutgoing(text, subtitle = "") {
    setMessages((prev) => [
      ...prev,
      {
        id: createLocalId(),
        role: "outgoing",
        sender: "我",
        subtitle,
        content: text,
        ts: Date.now()
      }
    ]);
  }

  async function ensureIdentity() {
    if (identityRef.current) {
      return identityRef.current;
    }
    if (identityPromiseRef.current) {
      return identityPromiseRef.current;
    }

    identityPromiseRef.current = generateClientIdentity()
      .then((identity) => {
        identityRef.current = identity;
        return identity;
      })
      .finally(() => {
        identityPromiseRef.current = null;
      });

    return identityPromiseRef.current;
  }

  async function revealMyPublicKey() {
    setPublicKeyBusy(true);
    try {
      const identity = await ensureIdentity();
      setMyPublicKey(identity.publicKeyBase64);
    } catch (error) {
      pushSystem(`生成公钥失败：${error?.message || "unknown error"}`);
    } finally {
      setPublicKeyBusy(false);
    }
  }

  async function copyMyPublicKey() {
    if (!myPublicKey) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(myPublicKey);
      } else {
        const temp = document.createElement("textarea");
        temp.value = myPublicKey;
        temp.setAttribute("readonly", "true");
        temp.style.position = "fixed";
        temp.style.opacity = "0";
        document.body.appendChild(temp);
        temp.select();
        document.execCommand("copy");
        document.body.removeChild(temp);
      }
      setCopyNotice("已复制");
    } catch {
      setCopyNotice("复制失败");
    }

    if (copyNoticeTimerRef.current) {
      clearTimeout(copyNoticeTimerRef.current);
    }
    copyNoticeTimerRef.current = window.setTimeout(() => {
      setCopyNotice("");
      copyNoticeTimerRef.current = 0;
    }, 1800);
  }

  async function copyMessageText(messageId, text) {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const temp = document.createElement("textarea");
        temp.value = text;
        temp.setAttribute("readonly", "true");
        temp.style.position = "fixed";
        temp.style.opacity = "0";
        document.body.appendChild(temp);
        temp.select();
        document.execCommand("copy");
        document.body.removeChild(temp);
      }
      setCopiedMessageId(messageId);
    } catch {
      pushSystem("消息复制失败");
      return;
    }

    if (messageCopyTimerRef.current) {
      clearTimeout(messageCopyTimerRef.current);
    }
    messageCopyTimerRef.current = window.setTimeout(() => {
      setCopiedMessageId("");
      messageCopyTimerRef.current = 0;
    }, 1600);
  }

  async function connect() {
    if (!canConnect) return;
    const cryptoIssue = getCryptoIssueMessage();
    if (cryptoIssue) {
      setStatus("error");
      setStatusHint(cryptoIssue);
      pushSystem(cryptoIssue);
      return;
    }

    const normalizedUrl = normalizeServerUrl(serverUrl);
    if (!normalizedUrl) {
      setStatus("error");
      setStatusHint("请填写服务器地址");
      return;
    }

    manualCloseRef.current = false;
    setStatus("connecting");
    setStatusHint("正在建立连接...");
    setCertFingerprint("");
    serverPublicKeyRef.current = "";
    fallbackTriedRef.current = false;

    setServerUrl(normalizedUrl);
    openSocket(normalizedUrl);
  }

  function openSocket(urlText) {
    let ws;
    const allowFallback = !fallbackTriedRef.current;

    try {
      ws = new WebSocket(urlText);
    } catch (error) {
      setStatus("error");
      setStatusHint(`连接失败：${error.message}`);
      return;
    }

    wsRef.current = ws;

    ws.onopen = async () => {
      setStatus("handshaking");
      setStatusHint("连接成功，等待服务端握手");
      pushSystem("连接建立，正在获取安全参数");
    };

    ws.onerror = () => {
      setStatusHint("连接异常，等待重试或关闭");
    };

    ws.onclose = (event) => {
      wsRef.current = null;
      if (manualCloseRef.current) {
        if (authTimeoutRef.current) {
          clearTimeout(authTimeoutRef.current);
          authTimeoutRef.current = 0;
        }
        setStatus("idle");
        setStatusHint("连接已关闭");
        pushSystem("已断开连接");
        return;
      }

      if (allowFallback && statusRef.current !== "ready") {
        const fallbackUrl = toggleWsProtocol(urlText);
        if (fallbackUrl) {
          fallbackTriedRef.current = true;
          setStatus("connecting");
          setStatusHint("连接失败，正在尝试另一种连接方式...");
          pushSystem(`切换连接方式重试：${fallbackUrl}`);
          openSocket(fallbackUrl);
          return;
        }
      }

      setStatus("error");
      setStatusHint("服务器关闭连接，可能是 ws/wss 协议或证书配置不匹配");
      pushSystem(`连接关闭 (${event.code})：${event.reason || "服务器主动断开"}`);
      if (authTimeoutRef.current) {
        clearTimeout(authTimeoutRef.current);
        authTimeoutRef.current = 0;
      }
    };

    ws.onmessage = async (event) => {
      if (typeof event.data !== "string") return;
      await handleMessage(event.data);
    };
  }

  function disconnect() {
    manualCloseRef.current = true;
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
  }

  async function handleMessage(rawText) {
    const plain = safeJsonParse(rawText);
    if (plain?.type === "publickey" && plain?.data?.publicKey) {
      await handleServerHello(plain.data);
      return;
    }

    const identity = identityRef.current;
    if (!identity) return;

    try {
      const decoded = await rsaDecryptChunked(identity.decryptPrivateKey, rawText);
      const secureMessage = safeJsonParse(decoded);
      if (!secureMessage) return;
      handleSecureMessage(secureMessage);
    } catch {
      pushSystem("收到无法解密的消息");
    }
  }

  async function handleServerHello(hello) {
    if (!hello.publicKey || !hello.authChallenge) {
      setStatus("error");
      setStatusHint("握手失败：服务端响应不完整");
      return;
    }

    serverPublicKeyRef.current = hello.publicKey;
    setCertFingerprint(hello.certFingerprintSha256 || "");
    setStatus("authenticating");
    setStatusHint("正在进行身份认证（首次可能需要几秒生成密钥）...");
    if (authTimeoutRef.current) {
      clearTimeout(authTimeoutRef.current);
    }
    authTimeoutRef.current = window.setTimeout(() => {
      if (statusRef.current === "authenticating") {
        setStatus("error");
        setStatusHint("身份认证超时，请重试");
        pushSystem("认证超时：可能是移动端密钥生成较慢或网络不稳定，请再次点击连接。");
        disconnect();
      }
    }, 20000);

    try {
      await sendAuth(hello.authChallenge);
      pushSystem("已发送认证请求");
    } catch (error) {
      setStatus("error");
      setStatusHint("认证失败");
      pushSystem(`认证发送失败：${error.message}`);
      disconnect();
    }
  }

  async function sendAuth(challenge) {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      throw new Error("连接不可用");
    }

    const identity = await ensureIdentity();
    if (!myPublicKey) {
      setMyPublicKey(identity.publicKeyBase64);
    }
    const user = displayName.trim() || createGuestName();
    if (user !== displayName) {
      setDisplayName(user);
    }

    const timestamp = unixSecondsNow();
    const nonce = createNonce();
    const signInput = ["publickey", user, identity.publicKeyBase64, challenge, timestamp, nonce].join("\n");
    const signature = await signText(identity.signPrivateKey, signInput);

    const envelope = {
      type: "publickey",
      key: user,
      data: {
        publicKey: identity.publicKeyBase64,
        challenge,
        timestamp,
        nonce,
        signature
      }
    };

    const cipher = await rsaEncryptChunked(serverPublicKeyRef.current, JSON.stringify(envelope));
    ws.send(cipher);
  }

  function handleSecureMessage(message) {
    if (message.type === "auth_ok") {
      if (authTimeoutRef.current) {
        clearTimeout(authTimeoutRef.current);
        authTimeoutRef.current = 0;
      }
      setStatus("ready");
      setStatusHint("连接就绪，可以开始聊天");
      pushSystem("认证成功");
      return;
    }

    if (message.type === "broadcast") {
      pushIncoming(message.key || "匿名用户", String(message.data ?? ""));
      return;
    }

    if (message.type === "forward") {
      const sender = message.key ? `私聊 ${summarizeKey(message.key)}` : "私聊";
      pushIncoming(sender, String(message.data ?? ""));
      return;
    }

    pushSystem(`收到未识别消息类型：${message.type}`);
  }

  async function sendMessage() {
    if (!canSend) return;

    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      setStatus("error");
      setStatusHint("连接已断开");
      return;
    }

    const identity = identityRef.current;
    const serverPublicKey = serverPublicKeyRef.current;
    if (!identity || !serverPublicKey) return;

    const text = draft.trim();
    const key = targetKey.trim();
    const type = key ? "forward" : "broadcast";
    const subtitle = key ? `目标 ${summarizeKey(key)}` : "广播";

    setSending(true);
    try {
      const timestamp = unixSecondsNow();
      const nonce = createNonce();
      const signInput = [type, key, text, timestamp, nonce].join("\n");
      const signature = await signText(identity.signPrivateKey, signInput);

      const envelope = {
        type,
        key,
        data: {
          payload: text,
          timestamp,
          nonce,
          signature
        }
      };

      const cipher = await rsaEncryptChunked(serverPublicKey, JSON.stringify(envelope));
      ws.send(cipher);
      pushOutgoing(text, subtitle);
      setDraft("");
    } catch (error) {
      pushSystem(`发送失败：${error.message}`);
    } finally {
      setSending(false);
    }
  }

  function onDraftKeyDown(event) {
    const isComposing =
      draftComposingRef.current ||
      event.isComposing ||
      event.nativeEvent?.isComposing ||
      event.keyCode === 229;
    if (isComposing) {
      return;
    }

    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  }

  function onTargetKeyDown(event) {
    const isComposing =
      targetComposingRef.current ||
      event.isComposing ||
      event.nativeEvent?.isComposing ||
      event.keyCode === 229;
    if (isComposing) {
      event.stopPropagation();
    }
  }

  return (
    <div className="page">
      <header className="hero">
        <div>
          <p className="hero-tag">OnlineMsg Chat</p>
          <h1>简洁、安全的在线消息界面</h1>
          <p className="hero-sub">默认自动处理连接和安全流程，你只需要聊天。</p>
        </div>
        <div className={`status-chip ${statusClass}`}>
          <span className="dot" />
          <span>{STATUS_TEXT[status]}</span>
        </div>
      </header>

      <main className="layout">
        <section className="chat-card">
          <div className="chat-head">
            <div>
              <strong>消息面板</strong>
              <p>{statusHint}</p>
            </div>
            <div className="head-actions">
              {canConnect ? (
                <button className="btn btn-main" onClick={connect}>
                  连接
                </button>
              ) : (
                <button className="btn btn-ghost" onClick={disconnect}>
                  断开
                </button>
              )}
              <button className="btn btn-ghost" onClick={() => setMessages([])}>
                清空
              </button>
            </div>
          </div>

          <div className="message-list">
            {messages.length === 0 ? (
              <div className="empty-tip">连接后即可开始聊天，支持广播和指定目标私聊。</div>
            ) : (
              messages.map((item) => (
                <article key={item.id} className={`msg ${item.role}`}>
                  {item.role === "system" ? (
                    <>
                      <p>{item.content}</p>
                      <div className="msg-actions">
                        <button className="btn btn-copy" onClick={() => copyMessageText(item.id, item.content)}>
                          {copiedMessageId === item.id ? "已复制" : "复制"}
                        </button>
                      </div>
                    </>
                  ) : (
                    <>
                      <div className="msg-head">
                        <strong>{item.sender}</strong>
                        {item.subtitle ? <span>{item.subtitle}</span> : null}
                        <time>{formatTime(item.ts)}</time>
                      </div>
                      <p>{item.content}</p>
                      <div className="msg-actions">
                        <button className="btn btn-copy" onClick={() => copyMessageText(item.id, item.content)}>
                          {copiedMessageId === item.id ? "已复制" : "复制"}
                        </button>
                      </div>
                    </>
                  )}
                </article>
              ))
            )}
          </div>

          <div className="composer">
            <textarea
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={onDraftKeyDown}
              onCompositionStart={() => {
                draftComposingRef.current = true;
              }}
              onCompositionEnd={() => {
                draftComposingRef.current = false;
              }}
              placeholder="输入消息，回车发送，Shift+回车换行"
              rows={3}
            />
            <button className="btn btn-main" onClick={sendMessage} disabled={!canSend}>
              {sending ? "发送中..." : "发送"}
            </button>
          </div>
        </section>

        <aside className="side-card">
          <div className="field">
            <label>显示名称</label>
            <input
              type="text"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              maxLength={64}
            />
          </div>

          <div className="field">
            <label>我的公钥</label>
            <div className="inline-actions">
              <button className="btn btn-ghost btn-sm" onClick={revealMyPublicKey} disabled={publicKeyBusy}>
                {publicKeyBusy ? "生成中..." : "查看/生成"}
              </button>
              <button className="btn btn-ghost btn-sm" onClick={copyMyPublicKey} disabled={!myPublicKey}>
                一键复制
              </button>
              {copyNotice ? <span className="copy-notice">{copyNotice}</span> : null}
            </div>
            <textarea
              value={myPublicKey}
              readOnly
              rows={4}
              className="readonly-text"
              placeholder="点击“查看/生成”显示你的公钥"
            />
          </div>

          <div className="field">
            <label>目标公钥（可选，留空即广播）</label>
            <textarea
              value={targetKey}
              onChange={(event) => setTargetKey(event.target.value)}
              onKeyDown={onTargetKeyDown}
              onCompositionStart={() => {
                targetComposingRef.current = true;
              }}
              onCompositionEnd={() => {
                targetComposingRef.current = false;
              }}
              rows={3}
              placeholder="仅在需要私聊时填写目标公钥"
            />
          </div>

          <details className="advanced" open={advancedOpen} onToggle={(e) => setAdvancedOpen(e.target.open)}>
            <summary>高级连接设置（可手动指定服务器）</summary>
            <div className="field">
              <label>服务器地址</label>
              <input
                type="text"
                value={serverUrl}
                onChange={(event) => setServerUrl(event.target.value)}
                placeholder="ws://example.com:13173/"
              />
            </div>
          </details>

          <div className="meta">
            <p>
              <span>当前状态</span>
              <strong>{STATUS_TEXT[status]}</strong>
            </p>
            <p>
              <span>证书指纹</span>
              <strong>{certFingerprint ? summarizeKey(certFingerprint) : "未获取"}</strong>
            </p>
          </div>
        </aside>
      </main>
    </div>
  );
}
