using OnlineMsgServer.Core;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Common
{
    class ForwardMessage : Message
    {
        public ForwardMessage()
        {
            Type = "forward";
            //收到客户端消息，并执行转发
        }
        public override Task Handler(string wsid, WebSocketSessionManager Sessions)
        {
            return Task.Run(() =>
            {
                try
                {
                    if (!UserService.IsAuthenticated(wsid))
                    {
                        Log.Security("forward_denied_unauthenticated", $"wsid={wsid}");
                        return;
                    }

                    if (string.IsNullOrWhiteSpace(Key))
                    {
                        Log.Security("forward_invalid_target", $"wsid={wsid}");
                        return;
                    }
                    string forwardPublickKey = Key.Trim();

                    if (!SignedMessagePayload.TryParse(Data, out SignedMessagePayload payload, out string parseError))
                    {
                        Log.Security("forward_payload_invalid", $"wsid={wsid} reason={parseError}");
                        return;
                    }

                    if (!SecurityValidator.VerifySignedMessage(wsid, Type, forwardPublickKey, payload, out string securityReason))
                    {
                        Log.Security("forward_security_failed", $"wsid={wsid} reason={securityReason}");
                        return;
                    }

                    if (PeerNetworkService.TryHandlePeerRelayForward(wsid, forwardPublickKey, payload))
                    {
                        return;
                    }

                    string fromPublicKey = UserService.GetUserPublicKeyByID(wsid)!;
                    if (!PeerNetworkService.TryMarkSeen(fromPublicKey, Type, forwardPublickKey, payload.Payload))
                    {
                        return;
                    }

                    bool delivered = PeerNetworkService.DeliverForwardToLocalClient(fromPublicKey, forwardPublickKey, payload.Payload);
                    if (delivered)
                    {
                        return;
                    }

                    string? excludePeerPublicKey = UserService.IsPeerNodeSession(wsid)
                        ? UserService.GetPeerPublicKeyBySessionId(wsid)
                        : null;

                    PeerNetworkService.RelayForwardMiss(forwardPublickKey, payload.Payload, excludePeerPublicKey);
                    Log.Security("forward_target_offline_or_untrusted", $"wsid={wsid}");
                }
                catch (Exception ex)
                {
                    Log.Security("forward_error", $"wsid={wsid} error={ex.Message}");
                }
            });
        }
    }
}
