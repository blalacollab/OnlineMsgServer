using OnlineMsgServer.Core;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Common
{
    class PublicKeyMessage : Message
    {
        public PublicKeyMessage()
        {
            Type = "publickey";
            //收到客户端公钥，添加到用户列表中，并返回自己的公钥
        }
        public override Task Handler(string wsid, WebSocketSessionManager Sessions)
        {
            return Task.Run(() =>
            {
                try
                {
                    if (!ClientRegistrationPayload.TryParse(Data, out ClientRegistrationPayload payload, out string parseError))
                    {
                        Log.Security("auth_payload_invalid", $"wsid={wsid} reason={parseError}");
                        CloseSession(Sessions, wsid, "auth payload invalid");
                        return;
                    }

                    if (!RsaService.IsPublicKeyValid(payload.PublicKey))
                    {
                        Log.Security("auth_publickey_invalid", $"wsid={wsid}");
                        CloseSession(Sessions, wsid, "public key invalid");
                        return;
                    }

                    SecurityConfig config = SecurityRuntime.Config;
                    if (!SecurityValidator.IsTimestampAcceptable(payload.Timestamp, config.MaxClockSkewSeconds, config.ChallengeTtlSeconds))
                    {
                        Log.Security("auth_timestamp_invalid", $"wsid={wsid} ts={payload.Timestamp}");
                        CloseSession(Sessions, wsid, "auth timestamp invalid");
                        return;
                    }

                    if (!UserService.TryGetChallenge(wsid, out string serverChallenge, out DateTime issuedAtUtc))
                    {
                        Log.Security("auth_challenge_missing", $"wsid={wsid}");
                        CloseSession(Sessions, wsid, "challenge missing");
                        return;
                    }

                    if (!string.Equals(serverChallenge, payload.Challenge, StringComparison.Ordinal))
                    {
                        Log.Security("auth_challenge_mismatch", $"wsid={wsid}");
                        CloseSession(Sessions, wsid, "challenge mismatch");
                        return;
                    }

                    if ((DateTime.UtcNow - issuedAtUtc).TotalSeconds > config.ChallengeTtlSeconds)
                    {
                        Log.Security("auth_challenge_expired", $"wsid={wsid}");
                        CloseSession(Sessions, wsid, "challenge expired");
                        return;
                    }

                    int idLength = Math.Min(wsid.Length, 8);
                    string userName = string.IsNullOrWhiteSpace(Key) ? $"anonymous-{wsid[..idLength]}" : Key.Trim();
                    if (userName.Length > 64)
                    {
                        userName = userName[..64];
                    }

                    string signingInput = ClientRegistrationPayload.BuildSigningInput(userName, payload.PublicKey, payload.Challenge, payload.Timestamp, payload.Nonce);
                    if (!RsaService.VerifySignature(payload.PublicKey, signingInput, payload.Signature))
                    {
                        Log.Security("auth_signature_invalid", $"wsid={wsid}");
                        CloseSession(Sessions, wsid, "auth signature invalid");
                        return;
                    }

                    if (!UserService.TryRecordNonce(wsid, payload.Nonce, payload.Timestamp, config.ReplayWindowSeconds, out string nonceReason))
                    {
                        Log.Security("auth_replay_nonce", $"wsid={wsid} reason={nonceReason}");
                        CloseSession(Sessions, wsid, "auth replay detected");
                        return;
                    }

                    bool isPeerNode = PeerNetworkService.IsPeerUserName(userName);
                    UserService.UserLogin(wsid, payload.PublicKey, userName, isPeerNode);
                    Log.Security("auth_success", $"wsid={wsid} user={userName}");

                    Message ack = new()
                    {
                        Type = "auth_ok",
                        Data = "authenticated"
                    };
                    string ackEncrypted = RsaService.EncryptForClient(payload.PublicKey, ack.ToJsonString());
                    SendToSession(Sessions, wsid, ackEncrypted);
                }
                catch (Exception ex)
                {
                    Log.Security("auth_error", $"wsid={wsid} error={ex.Message}");
                    CloseSession(Sessions, wsid, "auth error");
                }
            });
        }

        private static void SendToSession(WebSocketSessionManager sessions, string wsid, string message)
        {
            foreach (IWebSocketSession session in sessions.Sessions)
            {
                if (session.ID == wsid)
                {
                    session.Context.WebSocket.Send(message);
                    return;
                }
            }
        }

        private static void CloseSession(WebSocketSessionManager sessions, string wsid, string reason)
        {
            foreach (IWebSocketSession session in sessions.Sessions)
            {
                if (session.ID == wsid)
                {
                    session.Context.WebSocket.Close(WebSocketSharp.CloseStatusCode.PolicyViolation, reason);
                    return;
                }
            }
        }
    }
}
