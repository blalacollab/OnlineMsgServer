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
  handshaking: "连接中",
  authenticating: "连接中",
  ready: "已连接",
  error: "异常断开"
};
const STORAGE_DISPLAY_NAME_KEY = "oms_display_name";
const STORAGE_SERVER_URLS_KEY = "oms_server_urls";
const STORAGE_CURRENT_SERVER_URL_KEY = "oms_current_server_url";
const MAX_SERVER_URLS = 8;
const CHANNEL_BROADCAST = "broadcast";
const CHANNEL_PRIVATE = "private";

function isLikelyLocalHost(host) {
  const value = (host || "").toLowerCase();
  return value === "localhost" || value === "127.0.0.1" || value === "::1";
}

function getDefaultServerUrl() {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const host = window.location.hostname || "localhost";
  if (protocol === "wss" && !isLikelyLocalHost(host)) {
    return `${protocol}://${host}/msgws/`;
  }
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

function dedupeServerUrls(urls) {
  const result = [];
  const seen = new Set();
  for (const item of urls) {
    const normalized = normalizeServerUrl(String(item || ""));
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    result.push(normalized);
  }
  return result;
}

function getInitialServerUrls() {
  const fallback = [getDefaultServerUrl()];
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_SERVER_URLS_KEY);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return fallback;
    const normalized = dedupeServerUrls(parsed);
    return normalized.length > 0 ? normalized : fallback;
  } catch {
    return fallback;
  }
}

function getInitialServerUrl(serverUrls) {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_CURRENT_SERVER_URL_KEY);
    const normalized = normalizeServerUrl(stored || "");
    if (normalized) {
      return normalized;
    }
  } catch {
    // ignore
  }
  return serverUrls[0] || getDefaultServerUrl();
}

function appendServerUrl(list, urlText) {
  const normalized = normalizeServerUrl(urlText);
  if (!normalized) return list;
  const next = [normalized, ...list.filter((item) => item !== normalized)];
  return next.slice(0, MAX_SERVER_URLS);
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

function inferMessageChannel(item) {
  if (item?.channel === CHANNEL_PRIVATE || item?.channel === CHANNEL_BROADCAST) {
    return item.channel;
  }
  if (item?.sender === "私聊消息" || String(item?.subtitle || "").includes("私聊")) {
    return CHANNEL_PRIVATE;
  }
  return CHANNEL_BROADCAST;
}

export default function App() {
  const initialServerUrls = getInitialServerUrls();
  const AUTO_SCROLL_THRESHOLD = 24;

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
  const messageListRef = useRef(null);
  const stickToBottomRef = useRef(true);

  const [status, setStatus] = useState("idle");
  const [statusHint, setStatusHint] = useState("点击连接开始聊天");
  const [serverUrls, setServerUrls] = useState(initialServerUrls);
  const [serverUrl, setServerUrl] = useState(() => getInitialServerUrl(initialServerUrls));
  const [displayName, setDisplayName] = useState(getInitialDisplayName);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [targetKey, setTargetKey] = useState("");
  const [directMode, setDirectMode] = useState(false);
  const [draft, setDraft] = useState("");
  const [messages, setMessages] = useState([]);
  const [showSystemMessages, setShowSystemMessages] = useState(false);
  const [sending, setSending] = useState(false);
  const [certFingerprint, setCertFingerprint] = useState("");
  const [myPublicKey, setMyPublicKey] = useState("");
  const [publicKeyBusy, setPublicKeyBusy] = useState(false);
  const [copyNotice, setCopyNotice] = useState("");
  const [copiedMessageId, setCopiedMessageId] = useState("");
  const [activeMobileTab, setActiveMobileTab] = useState("chat");

  const isConnected = status === "ready";
  const canConnect = status === "idle" || status === "error";
  const canDisconnect = status !== "idle" && status !== "error";
  const canSend = isConnected && draft.trim().length > 0 && !sending;
  const activeChannel = directMode ? CHANNEL_PRIVATE : CHANNEL_BROADCAST;
  const mobileConnectText = useMemo(() => {
    if (status === "ready") return "已连接";
    if (status === "error") return "连接失败，点击重试";
    if (status === "idle") return "未连接，点击连接";
    return "连接中";
  }, [status]);
  const visibleMessages = useMemo(
    () =>
      messages.filter((item) => {
        if (item.role === "system") {
          return showSystemMessages;
        }
        return inferMessageChannel(item) === activeChannel;
      }),
    [messages, showSystemMessages, activeChannel]
  );

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
    try {
      globalThis.localStorage?.setItem(STORAGE_SERVER_URLS_KEY, JSON.stringify(serverUrls));
    } catch {
      // ignore storage failures
    }
  }, [serverUrls]);

  useEffect(() => {
    try {
      const value = serverUrl.trim();
      if (value) {
        globalThis.localStorage?.setItem(STORAGE_CURRENT_SERVER_URL_KEY, value);
      }
    } catch {
      // ignore storage failures
    }
  }, [serverUrl]);

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

  useEffect(() => {
    const root = document.documentElement;
    const vv = globalThis.visualViewport;

    const updateViewportVars = () => {
      const top = vv ? Math.max(0, vv.offsetTop) : 0;
      const height = vv ? vv.height : globalThis.innerHeight;
      root.style.setProperty("--vv-offset-top", `${top}px`);
      root.style.setProperty("--vv-height", `${height}px`);
    };

    updateViewportVars();

    vv?.addEventListener("resize", updateViewportVars);
    vv?.addEventListener("scroll", updateViewportVars);
    globalThis.addEventListener("resize", updateViewportVars);

    return () => {
      vv?.removeEventListener("resize", updateViewportVars);
      vv?.removeEventListener("scroll", updateViewportVars);
      globalThis.removeEventListener("resize", updateViewportVars);
      root.style.removeProperty("--vv-offset-top");
      root.style.removeProperty("--vv-height");
    };
  }, []);

  useEffect(() => {
    const list = messageListRef.current;
    if (!list) return;
    list.scrollTop = list.scrollHeight;
  }, []);

  useEffect(() => {
    const list = messageListRef.current;
    if (!list || !stickToBottomRef.current) return;
    list.scrollTop = list.scrollHeight;
  }, [visibleMessages.length]);

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

  function pushIncoming(sender, text, subtitle = "", channel = CHANNEL_BROADCAST) {
    setMessages((prev) => [
      ...prev,
      {
        id: createLocalId(),
        role: "incoming",
        sender,
        subtitle,
        channel,
        content: text,
        ts: Date.now()
      }
    ]);
  }

  function pushOutgoing(text, subtitle = "", channel = CHANNEL_BROADCAST) {
    setMessages((prev) => [
      ...prev,
      {
        id: createLocalId(),
        role: "outgoing",
        sender: "我",
        subtitle,
        channel,
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
    setStatusHint("正在连接服务器...");
    setCertFingerprint("");
    serverPublicKeyRef.current = "";
    fallbackTriedRef.current = false;

    setServerUrl(normalizedUrl);
    setServerUrls((prev) => appendServerUrl(prev, normalizedUrl));
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
      setStatusHint("已连接，正在准备聊天...");
      pushSystem("连接已建立");
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
          setStatusHint("正在自动重试连接...");
          pushSystem("连接方式切换中，正在重试");
          openSocket(fallbackUrl);
          return;
        }
      }

      setStatus("error");
      setStatusHint("连接已中断，请检查网络或服务器地址");
      pushSystem(`连接关闭 (${event.code})：${event.reason || "连接中断"}`);
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
    setStatusHint("正在完成身份验证...");
    if (authTimeoutRef.current) {
      clearTimeout(authTimeoutRef.current);
    }
    authTimeoutRef.current = window.setTimeout(() => {
      if (statusRef.current === "authenticating") {
        setStatus("error");
        setStatusHint("连接超时，请重试");
        pushSystem("认证超时，请检查网络后重试");
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
      setStatusHint("已连接，可以开始聊天");
      pushSystem("连接准备完成");
      return;
    }

    if (message.type === "broadcast") {
      pushIncoming(message.key || "匿名用户", String(message.data ?? ""), "", CHANNEL_BROADCAST);
      return;
    }

    if (message.type === "forward") {
      const sender = "私聊消息";
      pushIncoming(sender, String(message.data ?? ""), "", CHANNEL_PRIVATE);
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
    const key = directMode ? targetKey.trim() : "";
    if (directMode && !key) {
      setStatusHint("请先填写目标公钥，再发送私聊消息");
      return;
    }
    const type = key ? "forward" : "broadcast";
    const channel = key ? CHANNEL_PRIVATE : CHANNEL_BROADCAST;
    const subtitle = key ? `私聊 ${summarizeKey(key)}` : "";

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
      pushOutgoing(text, subtitle, channel);
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

  function onMessageListScroll(event) {
    const el = event.currentTarget;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickToBottomRef.current = distanceToBottom <= AUTO_SCROLL_THRESHOLD;
  }

  function saveCurrentServerUrl() {
    const normalizedUrl = normalizeServerUrl(serverUrl);
    if (!normalizedUrl) {
      setStatusHint("请输入有效的服务器地址");
      return;
    }
    setServerUrl(normalizedUrl);
    setServerUrls((prev) => appendServerUrl(prev, normalizedUrl));
    setStatusHint("服务器地址已保存");
  }

  function removeCurrentServerUrl() {
    const normalizedCurrent = normalizeServerUrl(serverUrl);
    const filtered = serverUrls.filter((item) => item !== normalizedCurrent);
    if (filtered.length === 0) {
      const fallback = getDefaultServerUrl();
      setServerUrls([fallback]);
      setServerUrl(fallback);
      setStatusHint("已恢复默认服务器地址");
      return;
    }
    setServerUrls(filtered);
    setServerUrl(filtered[0]);
    setStatusHint("已移除当前服务器地址");
  }

  return (
    <div className="page">
      <header className="hero">
        <div>
          <p className="hero-tag">OnlineMsg Chat</p>
        </div>
        <div className={`status-chip ${statusClass}`}>
          <span className="dot" />
          <span>{STATUS_TEXT[status]}</span>
        </div>
      </header>

      <main className={`layout mobile-${activeMobileTab}`}>
        <section className="chat-card">
          <div className="chat-head">
            <div className="chat-peer">
              <span className="chat-avatar">OM</span>
              <div>
                <strong>在线会话</strong>
                <p>{statusHint}</p>
              </div>
            </div>
            <div className="head-actions">
              {canConnect ? (
                <button className="btn btn-main desktop-only" onClick={connect}>
                  连接
                </button>
              ) : (
                <button className="btn btn-ghost desktop-only" onClick={disconnect}>
                  断开
                </button>
              )}
              <button className="btn btn-ghost desktop-only" onClick={() => setMessages([])}>
                清空
              </button>
            </div>
          </div>

          <div className="chat-mode-strip mobile-only">
            <div className="chat-mode-left">
              <span className="mode-strip-icon" aria-hidden="true">
                OM
              </span>
              <button
                className={`btn btn-mode ${!directMode ? "active" : ""}`}
                onClick={() => setDirectMode(false)}
                type="button"
              >
                广播
              </button>
              <button
                className={`btn btn-mode ${directMode ? "active" : ""}`}
                onClick={() => setDirectMode(true)}
                type="button"
              >
                私聊
              </button>
            </div>
            <div className="chat-mode-right">
              <button
                className={`btn mobile-conn-btn ${statusClass} ${canConnect ? "actionable" : ""}`}
                type="button"
                onClick={canConnect ? connect : undefined}
                disabled={!canConnect}
                aria-label={mobileConnectText}
                title={mobileConnectText}
              >
                <span className="dot" />
                <span>{mobileConnectText}</span>
              </button>
            </div>
          </div>

          {directMode ? (
            <div className="chat-target-box mobile-only">
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
                rows={2}
                placeholder="私聊模式：粘贴目标公钥"
              />
            </div>
          ) : null}

          <div className="message-list" ref={messageListRef} onScroll={onMessageListScroll}>
            {visibleMessages.length === 0 ? (
              <div className="empty-tip">连接后即可聊天。默认广播，切换到私聊后可填写目标公钥。</div>
            ) : (
              visibleMessages.map((item) => (
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
            <div className="composer-input-wrap">
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
                placeholder="输入消息"
                rows={1}
              />
            </div>
            <button className="btn btn-main btn-send" onClick={sendMessage} disabled={!canSend}>
              {sending ? "发送中..." : "发送"}
            </button>
          </div>
        </section>

        <aside className="side-card">
          <div className="settings-head mobile-only">
            <strong>设置</strong>
            <button className="btn btn-ghost btn-sm" type="button" onClick={() => setActiveMobileTab("chat")}>
              返回聊天
            </button>
          </div>

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
            <label>连接控制</label>
            <button className="btn btn-ghost btn-sm btn-disconnect" type="button" onClick={disconnect} disabled={!canDisconnect}>
              主动断开连接
            </button>
          </div>

          <div className="field">
            <label>发送方式</label>
            <div className="mode-switch">
              <button
                className={`btn btn-mode ${!directMode ? "active" : ""}`}
                onClick={() => setDirectMode(false)}
                type="button"
              >
                广播
              </button>
              <button
                className={`btn btn-mode ${directMode ? "active" : ""}`}
                onClick={() => setDirectMode(true)}
                type="button"
              >
                私聊
              </button>
            </div>
          </div>

          {directMode ? (
            <div className="field">
              <label>目标公钥</label>
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
                placeholder="粘贴对方公钥后发送"
              />
            </div>
          ) : null}

          <details className="advanced" open={advancedOpen} onToggle={(e) => setAdvancedOpen(e.target.open)}>
            <summary>高级连接设置</summary>
            <div className="field">
              <label>已保存服务器</label>
              <div className="server-list-row">
                <select value={serverUrl} onChange={(event) => setServerUrl(event.target.value)}>
                  {serverUrls.map((item) => (
                    <option key={item} value={item}>
                      {item}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="field">
              <label>服务器地址</label>
              <input
                type="text"
                value={serverUrl}
                onChange={(event) => setServerUrl(event.target.value)}
                placeholder="wss://example.com/msgws/"
              />
              <div className="server-actions">
                <button className="btn btn-ghost btn-sm" type="button" onClick={saveCurrentServerUrl}>
                  保存地址
                </button>
                <button className="btn btn-ghost btn-sm" type="button" onClick={removeCurrentServerUrl}>
                  删除当前
                </button>
              </div>
            </div>
          </details>

          <details className="advanced">
            <summary>身份与安全</summary>
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
          </details>

          <details className="advanced">
            <summary>诊断信息</summary>
            <div className="meta">
              <p>
                <span>连接提示</span>
                <strong>{statusHint}</strong>
              </p>
              <p>
                <span>当前状态</span>
                <strong>{STATUS_TEXT[status]}</strong>
              </p>
              <p>
                <span>证书指纹</span>
                <strong>{certFingerprint ? summarizeKey(certFingerprint) : "未获取"}</strong>
              </p>
              <label className="toggle-row">
                <input
                  type="checkbox"
                  checked={showSystemMessages}
                  onChange={(event) => setShowSystemMessages(event.target.checked)}
                />
                <span>显示系统消息</span>
              </label>
            </div>
          </details>
        </aside>
      </main>

      <nav className="mobile-nav">
        <button
          className={`mobile-nav-btn ${activeMobileTab === "chat" ? "active" : ""}`}
          type="button"
          onClick={() => setActiveMobileTab("chat")}
        >
          <svg viewBox="0 0 24 24" className="mobile-nav-icon" aria-hidden="true">
            <path d="M5 6h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H10l-5 4v-4H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z" />
          </svg>
          <span className="mobile-nav-label">聊天</span>
        </button>
        <button
          className={`mobile-nav-btn ${activeMobileTab === "settings" ? "active" : ""}`}
          type="button"
          onClick={() => setActiveMobileTab("settings")}
        >
          <svg viewBox="0 0 24 24" className="mobile-nav-icon" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" />
          </svg>
          <span className="mobile-nav-label">设置</span>
        </button>
      </nav>
    </div>
  );
}
