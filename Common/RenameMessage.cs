using OnlineMsgServer.Core;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Common
{
    class RenameMessage : Message
    {
        public RenameMessage()
        {
            Type = "rename";
            Key = "";
        }

        public override Task Handler(string wsid, WebSocketSessionManager Sessions)
        {
            return Task.Run(() =>
            {
                try
                {
                    if (!UserService.IsAuthenticated(wsid))
                    {
                        Log.Security("rename_denied_unauthenticated", $"wsid={wsid}");
                        return;
                    }

                    string key = Key?.Trim() ?? "";
                    if (!SignedMessagePayload.TryParse(Data, out SignedMessagePayload payload, out string parseError))
                    {
                        Log.Security("rename_payload_invalid", $"wsid={wsid} reason={parseError}");
                        SendEncryptedResult(Sessions, wsid, "rename_error", "rename payload invalid");
                        return;
                    }

                    if (!SecurityValidator.VerifySignedMessage(wsid, Type, key, payload, out string securityReason))
                    {
                        Log.Security("rename_security_failed", $"wsid={wsid} reason={securityReason}");
                        SendEncryptedResult(Sessions, wsid, "rename_error", "rename signature invalid");
                        return;
                    }

                    string nextName = payload.Payload.Trim();
                    if (string.IsNullOrWhiteSpace(nextName))
                    {
                        Log.Security("rename_invalid_name", $"wsid={wsid} reason=blank");
                        SendEncryptedResult(Sessions, wsid, "rename_error", "display name cannot be empty");
                        return;
                    }

                    if (!UserService.IsPeerNodeSession(wsid) && PeerNetworkService.IsPeerUserName(nextName))
                    {
                        Log.Security("rename_invalid_name", $"wsid={wsid} reason=peer_prefix");
                        SendEncryptedResult(Sessions, wsid, "rename_error", "display name uses reserved prefix");
                        return;
                    }

                    if (!UserService.TryUpdateUserName(wsid, nextName, out string appliedName))
                    {
                        Log.Security("rename_update_failed", $"wsid={wsid}");
                        SendEncryptedResult(Sessions, wsid, "rename_error", "display name update failed");
                        return;
                    }

                    Log.Security("rename_success", $"wsid={wsid} user={appliedName}");
                    SendEncryptedResult(Sessions, wsid, "rename_ok", appliedName);
                }
                catch (Exception ex)
                {
                    Log.Security("rename_error", $"wsid={wsid} error={ex.Message}");
                    SendEncryptedResult(Sessions, wsid, "rename_error", "display name update failed");
                }
            });
        }

        private static void SendEncryptedResult(
            WebSocketSessionManager sessions,
            string wsid,
            string type,
            string data
        )
        {
            string? publicKey = UserService.GetUserPublicKeyByID(wsid);
            if (string.IsNullOrWhiteSpace(publicKey))
            {
                return;
            }

            Message result = new()
            {
                Type = type,
                Data = data
            };
            string encrypted = RsaService.EncryptForClient(publicKey, result.ToJsonString());

            foreach (IWebSocketSession session in sessions.Sessions)
            {
                if (session.ID == wsid)
                {
                    session.Context.WebSocket.Send(encrypted);
                    return;
                }
            }
        }
    }
}
