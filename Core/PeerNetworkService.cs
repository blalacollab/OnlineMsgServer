using System.IO;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OnlineMsgServer.Common;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Core
{
    internal static class PeerNetworkService
    {
        private static readonly object _lock = new();
        private static readonly Dictionary<string, PeerOutboundClient> _outboundPeers = [];

        private static SecurityConfig _config = SecurityRuntime.Config;
        private static SeenMessageCache _seenCache = new(120);
        private static WebSocketSessionManager? _sessions;
        private static CancellationTokenSource? _cts;

        public static void Initialize(SecurityConfig config, WebSocketSessionManager sessions)
        {
            lock (_lock)
            {
                _config = config;
                _sessions = sessions;
                _seenCache = new SeenMessageCache(config.SeenCacheSeconds);
            }
        }

        public static void Start()
        {
            lock (_lock)
            {
                if (_cts != null)
                {
                    return;
                }

                _cts = new CancellationTokenSource();
                foreach (string peerUrl in _config.PeerUrls)
                {
                    if (_outboundPeers.ContainsKey(peerUrl))
                    {
                        continue;
                    }

                    PeerOutboundClient peerClient = new(peerUrl, BuildPeerDisplayName(peerUrl));
                    _outboundPeers[peerUrl] = peerClient;
                    peerClient.Start(_cts.Token);
                }
            }
        }

        public static void Stop()
        {
            CancellationTokenSource? cts;
            List<PeerOutboundClient> peers;

            lock (_lock)
            {
                cts = _cts;
                _cts = null;
                peers = [.. _outboundPeers.Values];
                _outboundPeers.Clear();
            }

            cts?.Cancel();
            foreach (PeerOutboundClient peer in peers)
            {
                peer.Dispose();
            }
        }

        public static bool IsPeerUserName(string? userName)
        {
            return !string.IsNullOrWhiteSpace(userName) &&
                   userName.StartsWith(_config.PeerUserPrefix, StringComparison.Ordinal);
        }

        public static string GetPeerUserName()
        {
            string userName = $"{_config.PeerUserPrefix}{_config.PeerNodeName}".Trim();
            return userName.Length <= 64 ? userName : userName[..64];
        }

        public static string GetVisibleUserName(string? userName)
        {
            if (string.IsNullOrWhiteSpace(userName))
            {
                return "";
            }

            string trimmed = userName.Trim();
            if (!IsPeerUserName(trimmed))
            {
                return trimmed;
            }

            string visibleName = trimmed[_config.PeerUserPrefix.Length..].Trim();
            return string.IsNullOrWhiteSpace(visibleName) ? trimmed : visibleName;
        }

        public static bool TryMarkSeen(string senderIdentity, string type, string key, string payload)
        {
            return _seenCache.TryMark(senderIdentity, type, key, payload);
        }

        public static bool TryHandlePeerRelayForward(string wsid, string targetKey, SignedMessagePayload payload)
        {
            if (!UserService.IsPeerNodeSession(wsid))
            {
                return false;
            }

            if (!string.Equals(targetKey, RsaService.GetRsaPublickKey(), StringComparison.Ordinal))
            {
                return false;
            }

            if (!PeerRelayEnvelope.TryParse(payload.Payload, out PeerRelayEnvelope envelope))
            {
                return false;
            }

            string sourcePublicKey = UserService.GetPeerPublicKeyBySessionId(wsid) ?? "";
            string sourceDisplayName = GetVisibleUserName(UserService.GetUserNameByID(wsid));
            ProcessPeerEnvelope(sourcePublicKey, sourceDisplayName, envelope);
            return true;
        }

        public static void RelayForwardMiss(string targetKey, string payload, string? excludePeerPublicKey = null)
        {
            PeerRelayEnvelope envelope = new()
            {
                Kind = "forward",
                TargetKey = targetKey,
                Payload = payload
            };

            RelayPeerEnvelope(envelope, excludePeerPublicKey);
        }

        public static void RelayBroadcast(string payload, string? excludePeerPublicKey = null)
        {
            PeerRelayEnvelope envelope = new()
            {
                Kind = "broadcast",
                TargetKey = "",
                Payload = payload
            };

            RelayPeerEnvelope(envelope, excludePeerPublicKey);
        }

        public static void DeliverBroadcastToLocalClients(string senderName, string payload, string? excludeSessionId = null)
        {
            WebSocketSessionManager sessions = RequireSessions();
            Message response = new()
            {
                Type = "broadcast",
                Data = payload,
                Key = senderName
            };
            string jsonString = response.ToJsonString();

            foreach (IWebSocketSession session in sessions.Sessions)
            {
                if (session.ID == excludeSessionId)
                {
                    continue;
                }

                if (!UserService.IsAuthenticated(session.ID) || UserService.IsPeerNodeSession(session.ID))
                {
                    continue;
                }

                string? publicKey = UserService.GetUserPublicKeyByID(session.ID);
                if (string.IsNullOrWhiteSpace(publicKey))
                {
                    continue;
                }

                string encryptString = RsaService.EncryptForClient(publicKey, jsonString);
                session.Context.WebSocket.Send(encryptString);
            }
        }

        public static bool DeliverForwardToLocalClient(string senderPublicKey, string targetPublicKey, string payload)
        {
            WebSocketSessionManager sessions = RequireSessions();
            List<User> userList = UserService.GetUserListByPublicKey(targetPublicKey, includePeerNodes: false);
            if (userList.Count == 0)
            {
                return false;
            }

            Message response = new()
            {
                Type = "forward",
                Data = payload,
                Key = senderPublicKey
            };
            string jsonString = response.ToJsonString();
            string encryptString = RsaService.EncryptForClient(targetPublicKey, jsonString);

            foreach (IWebSocketSession session in sessions.Sessions)
            {
                if (userList.Exists(u => u.ID == session.ID))
                {
                    session.Context.WebSocket.Send(encryptString);
                    return true;
                }
            }

            return false;
        }

        private static void ProcessPeerEnvelope(string sourcePublicKey, string sourceDisplayName, PeerRelayEnvelope envelope)
        {
            if (!TryMarkSeen(sourcePublicKey, envelope.Kind, envelope.TargetKey, envelope.Payload))
            {
                return;
            }

            switch (envelope.Kind)
            {
                case "broadcast":
                    DeliverBroadcastToLocalClients(sourceDisplayName, envelope.Payload);
                    RelayPeerEnvelope(envelope, sourcePublicKey);
                    break;
                case "forward":
                    bool delivered = DeliverForwardToLocalClient(sourcePublicKey, envelope.TargetKey, envelope.Payload);
                    if (!delivered)
                    {
                        RelayPeerEnvelope(envelope, sourcePublicKey);
                    }
                    break;
                default:
                    Log.Security("peer_envelope_invalid_kind", $"kind={envelope.Kind}");
                    break;
            }
        }

        private static void RelayPeerEnvelope(PeerRelayEnvelope envelope, string? excludePeerPublicKey)
        {
            string payloadJson = envelope.ToJsonString();
            HashSet<string> sentPeerKeys = [];

            foreach (PeerOutboundClient peer in SnapshotOutboundPeers())
            {
                string? remotePublicKey = peer.RemotePublicKey;
                if (!peer.IsAuthenticated || string.IsNullOrWhiteSpace(remotePublicKey))
                {
                    continue;
                }

                if (string.Equals(remotePublicKey, excludePeerPublicKey, StringComparison.Ordinal) ||
                    !sentPeerKeys.Add(remotePublicKey))
                {
                    continue;
                }

                peer.TrySendRelayEnvelope(payloadJson);
            }

            SendPeerEnvelopeToInboundPeers(payloadJson, sentPeerKeys, excludePeerPublicKey);
        }

        private static void SendPeerEnvelopeToInboundPeers(string payloadJson, HashSet<string> sentPeerKeys, string? excludePeerPublicKey)
        {
            WebSocketSessionManager sessions = RequireSessions();
            Message response = new()
            {
                Type = "forward",
                Key = RsaService.GetRsaPublickKey(),
                Data = payloadJson
            };
            string jsonString = response.ToJsonString();

            foreach (User user in UserService.GetAuthenticatedUsers(includePeerNodes: true))
            {
                if (!user.IsPeerNode || string.IsNullOrWhiteSpace(user.PublicKey))
                {
                    continue;
                }

                if (string.Equals(user.PublicKey, excludePeerPublicKey, StringComparison.Ordinal) ||
                    !sentPeerKeys.Add(user.PublicKey))
                {
                    continue;
                }

                string encryptString = RsaService.EncryptForClient(user.PublicKey, jsonString);
                foreach (IWebSocketSession session in sessions.Sessions)
                {
                    if (session.ID == user.ID)
                    {
                        session.Context.WebSocket.Send(encryptString);
                        break;
                    }
                }
            }
        }

        private static List<PeerOutboundClient> SnapshotOutboundPeers()
        {
            lock (_lock)
            {
                return [.. _outboundPeers.Values];
            }
        }

        private static WebSocketSessionManager RequireSessions()
        {
            return _sessions ?? throw new InvalidOperationException("peer network sessions not initialized");
        }

        private static string BuildPeerDisplayName(string peerUrl)
        {
            try
            {
                Uri uri = new(peerUrl);
                string displayName = $"{_config.PeerUserPrefix}{BuildGuestAlias(uri.Host)}";
                return displayName.Length <= 64 ? displayName : displayName[..64];
            }
            catch
            {
                return GetPeerUserName();
            }
        }

        private static void HandlePeerSocketMessage(PeerOutboundClient peer, string text)
        {
            if (TryHandlePeerHello(peer, text))
            {
                return;
            }

            string plainText;
            try
            {
                plainText = RsaService.Decrypt(text);
            }
            catch
            {
                return;
            }

            using JsonDocument doc = JsonDocument.Parse(plainText);
            JsonElement root = doc.RootElement;
            if (!root.TryGetProperty("type", out JsonElement typeElement) || typeElement.ValueKind != JsonValueKind.String)
            {
                return;
            }

            string type = typeElement.GetString() ?? "";
            switch (type)
            {
                case "auth_ok":
                    peer.MarkAuthenticated();
                    Log.Debug($"peer auth ok {peer.PeerUrl}");
                    return;
                case "forward":
                case "broadcast":
                    if (!root.TryGetProperty("data", out JsonElement dataElement))
                    {
                        return;
                    }

                    string payload = ExtractPayloadString(dataElement);
                    if (PeerRelayEnvelope.TryParse(payload, out PeerRelayEnvelope envelope))
                    {
                        ProcessPeerEnvelope(peer.RemotePublicKey ?? "", GetVisibleUserName(peer.DisplayName), envelope);
                    }
                    return;
                default:
                    return;
            }
        }

        private static string BuildGuestAlias(string seed)
        {
            byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(seed));
            int value = BitConverter.ToInt32(hash, 0) & int.MaxValue;
            return $"guest-{(value % 900000) + 100000:D6}";
        }

        private static bool TryHandlePeerHello(PeerOutboundClient peer, string text)
        {
            try
            {
                using JsonDocument doc = JsonDocument.Parse(text);
                JsonElement root = doc.RootElement;
                if (!root.TryGetProperty("type", out JsonElement typeElement) ||
                    typeElement.ValueKind != JsonValueKind.String ||
                    !string.Equals(typeElement.GetString(), "publickey", StringComparison.Ordinal))
                {
                    return false;
                }

                if (!root.TryGetProperty("data", out JsonElement dataElement) || dataElement.ValueKind != JsonValueKind.Object)
                {
                    return false;
                }

                if (!dataElement.TryGetProperty("publicKey", out JsonElement publicKeyElement) ||
                    publicKeyElement.ValueKind != JsonValueKind.String ||
                    !dataElement.TryGetProperty("authChallenge", out JsonElement challengeElement) ||
                    challengeElement.ValueKind != JsonValueKind.String)
                {
                    return false;
                }

                string remotePublicKey = publicKeyElement.GetString() ?? "";
                string challenge = challengeElement.GetString() ?? "";
                if (string.IsNullOrWhiteSpace(remotePublicKey) || string.IsNullOrWhiteSpace(challenge))
                {
                    return false;
                }

                peer.SetRemotePublicKey(remotePublicKey);
                SendPeerAuth(peer, remotePublicKey, challenge);
                return true;
            }
            catch
            {
                return false;
            }
        }

        private static void SendPeerAuth(PeerOutboundClient peer, string remotePublicKey, string challenge)
        {
            string localPublicKey = RsaService.GetRsaPublickKey();
            string userName = GetPeerUserName();
            long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            string nonce = SecurityValidator.CreateNonce();
            string signingInput = ClientRegistrationPayload.BuildSigningInput(userName, localPublicKey, challenge, timestamp, nonce);
            string signature = RsaService.Sign(signingInput);

            Message request = new()
            {
                Type = "publickey",
                Key = userName,
                Data = new
                {
                    publicKey = localPublicKey,
                    challenge,
                    timestamp,
                    nonce,
                    signature
                }
            };

            string cipherText = RsaService.EncryptForClient(remotePublicKey, request.ToJsonString());
            peer.TrySendRaw(cipherText);
        }

        private static string ExtractPayloadString(JsonElement dataElement)
        {
            return dataElement.ValueKind == JsonValueKind.String
                ? dataElement.GetString() ?? ""
                : dataElement.GetRawText();
        }

        private sealed class PeerOutboundClient(string peerUrl, string displayName) : IDisposable
        {
            private readonly object _socketLock = new();

            private ClientWebSocket? _socket;
            private Task? _runTask;
            private CancellationToken _cancellationToken;

            public string PeerUrl { get; } = peerUrl;
            public string DisplayName { get; } = displayName;
            public string? RemotePublicKey { get; private set; }
            public bool IsAuthenticated { get; private set; }

            public void Start(CancellationToken cancellationToken)
            {
                _cancellationToken = cancellationToken;
                _runTask = Task.Run(RunAsync, cancellationToken);
            }

            public void SetRemotePublicKey(string remotePublicKey)
            {
                RemotePublicKey = remotePublicKey;
            }

            public void MarkAuthenticated()
            {
                IsAuthenticated = true;
            }

            public bool TrySendRelayEnvelope(string relayPayload)
            {
                if (!IsAuthenticated || string.IsNullOrWhiteSpace(RemotePublicKey))
                {
                    return false;
                }

                long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                string nonce = SecurityValidator.CreateNonce();
                string targetKey = RemotePublicKey;
                string signature = RsaService.Sign(SignedMessagePayload.BuildSigningInput("forward", targetKey, relayPayload, timestamp, nonce));

                Message request = new()
                {
                    Type = "forward",
                    Key = targetKey,
                    Data = new
                    {
                        payload = relayPayload,
                        timestamp,
                        nonce,
                        signature
                    }
                };

                string cipherText = RsaService.EncryptForClient(RemotePublicKey, request.ToJsonString());
                return TrySendRaw(cipherText);
            }

            public bool TrySendRaw(string text)
            {
                ClientWebSocket? socket;
                lock (_socketLock)
                {
                    socket = _socket;
                }

                if (socket == null || socket.State != WebSocketState.Open)
                {
                    return false;
                }

                try
                {
                    byte[] payload = Encoding.UTF8.GetBytes(text);
                    socket.SendAsync(payload, WebSocketMessageType.Text, true, _cancellationToken)
                        .GetAwaiter()
                        .GetResult();
                    return true;
                }
                catch (Exception ex)
                {
                    Log.Security("peer_send_failed", $"peer={PeerUrl} error={ex.Message}");
                    return false;
                }
            }

            public void Dispose()
            {
                ClientWebSocket? socket;
                lock (_socketLock)
                {
                    socket = _socket;
                    _socket = null;
                }

                IsAuthenticated = false;
                RemotePublicKey = null;

                if (socket == null)
                {
                    return;
                }

                try
                {
                    socket.Abort();
                }
                catch
                {
                    // ignore
                }

                try
                {
                    socket.Dispose();
                }
                catch
                {
                    // ignore
                }
            }

            private async Task RunAsync()
            {
                while (!_cancellationToken.IsCancellationRequested)
                {
                    ClientWebSocket socket = new();
                    if (PeerUrl.StartsWith("wss://", StringComparison.OrdinalIgnoreCase))
                    {
                        socket.Options.RemoteCertificateValidationCallback = static (_, _, _, _) => true;
                    }

                    lock (_socketLock)
                    {
                        _socket = socket;
                    }

                    IsAuthenticated = false;
                    RemotePublicKey = null;

                    try
                    {
                        await socket.ConnectAsync(new Uri(PeerUrl), _cancellationToken);
                        Log.Debug($"peer open {PeerUrl}");
                        await ReceiveLoopAsync(socket, _cancellationToken);
                    }
                    catch (OperationCanceledException) when (_cancellationToken.IsCancellationRequested)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        Log.Security("peer_connect_failed", $"peer={PeerUrl} error={ex}");
                    }
                    finally
                    {
                        string closeReason = "";
                        try
                        {
                            closeReason = socket.CloseStatusDescription
                                ?? socket.CloseStatus?.ToString()
                                ?? "";
                        }
                        catch
                        {
                            // ignore
                        }

                        Dispose();
                        Log.Debug($"peer close {PeerUrl} {closeReason}");
                    }

                    if (_cancellationToken.IsCancellationRequested)
                    {
                        break;
                    }

                    await Task.Delay(TimeSpan.FromSeconds(_config.PeerReconnectSeconds), _cancellationToken)
                        .ContinueWith(_ => { }, TaskScheduler.Default);
                }
            }

            private async Task ReceiveLoopAsync(ClientWebSocket socket, CancellationToken cancellationToken)
            {
                byte[] buffer = new byte[16 * 1024];
                using MemoryStream messageBuffer = new();

                while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
                {
                    WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, cancellationToken);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        break;
                    }

                    if (result.Count > 0)
                    {
                        messageBuffer.Write(buffer, 0, result.Count);
                    }

                    if (!result.EndOfMessage)
                    {
                        continue;
                    }

                    if (result.MessageType != WebSocketMessageType.Text)
                    {
                        messageBuffer.SetLength(0);
                        continue;
                    }

                    string text = Encoding.UTF8.GetString(messageBuffer.GetBuffer(), 0, (int)messageBuffer.Length);
                    messageBuffer.SetLength(0);

                    if (!string.IsNullOrWhiteSpace(text))
                    {
                        HandlePeerSocketMessage(this, text);
                    }
                }
            }
        }
    }
}
