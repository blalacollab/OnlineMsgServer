using OnlineMsgServer.Core;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Common
{
    class BroadcastMessage : Message
    {
        public BroadcastMessage()
        {
            Type = "broadcast";
            //收到客户端消息，并执行广播
        }
        public override Task Handler(string wsid, WebSocketSessionManager Sessions)
        {
            return Task.Run(() =>
            {
                try
                {
                    if (!UserService.IsAuthenticated(wsid))
                    {
                        Log.Security("broadcast_denied_unauthenticated", $"wsid={wsid}");
                        return;
                    }

                    string key = Key?.Trim() ?? "";
                    if (!SignedMessagePayload.TryParse(Data, out SignedMessagePayload payload, out string parseError))
                    {
                        Log.Security("broadcast_payload_invalid", $"wsid={wsid} reason={parseError}");
                        return;
                    }

                    if (!SecurityValidator.VerifySignedMessage(wsid, Type, key, payload, out string securityReason))
                    {
                        Log.Security("broadcast_security_failed", $"wsid={wsid} reason={securityReason}");
                        return;
                    }

                    string senderPublicKey = UserService.GetUserPublicKeyByID(wsid)!;
                    if (!PeerNetworkService.TryMarkSeen(senderPublicKey, Type, key, payload.Payload))
                    {
                        return;
                    }

                    string senderName = UserService.GetUserNameByID(wsid) ?? "anonymous";
                    PeerNetworkService.DeliverBroadcastToLocalClients(senderName, payload.Payload, wsid);

                    string? excludePeerPublicKey = UserService.IsPeerNodeSession(wsid)
                        ? UserService.GetPeerPublicKeyBySessionId(wsid)
                        : null;

                    PeerNetworkService.RelayBroadcast(payload.Payload, excludePeerPublicKey);
                }
                catch (Exception ex)
                {
                    Log.Security("broadcast_error", $"wsid={wsid} error={ex.Message}");
                }
            });
        }
    }
}
