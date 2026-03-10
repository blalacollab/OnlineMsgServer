using System.Net;
using OnlineMsgServer.Common;
using WebSocketSharp;
using WebSocketSharp.Server;
using ErrorEventArgs = WebSocketSharp.ErrorEventArgs;

namespace OnlineMsgServer.Core
{
    class WsService : WebSocketBehavior
    {
        private IPEndPoint iPEndPoint = new(IPAddress.Any, 0);
        private static readonly object _abuseLock = new();
        private static readonly Dictionary<string, DateTime> _ipBlockedUntil = [];

        public WsService()
        {
            // OkHttp/Android on some paths fails to surface a compressed first message.
            // Keep the handshake/hello packet uncompressed for maximum client compatibility.
            IgnoreExtensions = true;
        }

        protected override async void OnMessage(MessageEventArgs e)
        {
            SecurityConfig config = SecurityRuntime.Config;
            try
            {
                string ip = iPEndPoint.Address.ToString();
                if (IsIpBlocked(ip))
                {
                    Common.Log.Security("message_rejected_ip_blocked", $"wsid={ID} ip={ip}");
                    Context.WebSocket.Close(CloseStatusCode.PolicyViolation, "ip blocked");
                    return;
                }

                int messageSize = e.RawData?.Length ?? 0;
                if (messageSize > config.MaxMessageBytes)
                {
                    Common.Log.Security("message_too_large", $"wsid={ID} size={messageSize}");
                    BlockIp(ip, config.IpBlockSeconds);
                    Context.WebSocket.Close(CloseStatusCode.PolicyViolation, "message too large");
                    return;
                }

                if (UserService.IsRateLimitExceeded(ID, config.RateLimitCount, config.RateLimitWindowSeconds))
                {
                    Common.Log.Security("rate_limited", $"wsid={ID} ip={ip}");
                    BlockIp(ip, config.IpBlockSeconds);
                    Context.WebSocket.Close(CloseStatusCode.PolicyViolation, "rate limited");
                    return;
                }

                Common.Log.Debug(ID + " " + Context.UserEndPoint.ToString() + ":" + e.Data);
                //从base64字符串解密
                string decryptString = RsaService.Decrypt(e.Data);
                //json 反序列化
                Message? message = Message.JsonStringParse(decryptString);
                if (message != null)
                {
                    await message.HandlerAndMeasure(ID, Sessions);
                }
            }
            catch (Exception ex)
            {
                Common.Log.Security("message_process_error", $"wsid={ID} error={ex.Message}");
            }
        }

        protected override void OnOpen()
        {
            iPEndPoint = Context.UserEndPoint;
            SecurityConfig config = SecurityRuntime.Config;
            string ip = iPEndPoint.Address.ToString();

            if (IsIpBlocked(ip))
            {
                Common.Log.Security("connection_blocked_ip", $"ip={ip}");
                Context.WebSocket.Close(CloseStatusCode.PolicyViolation, "ip blocked");
                return;
            }

            if (UserService.GetConnectionCount() >= config.MaxConnections)
            {
                Common.Log.Security("connection_rejected_max", $"ip={ip} max={config.MaxConnections}");
                Context.WebSocket.Close(CloseStatusCode.PolicyViolation, "server busy");
                return;
            }

            string challenge = SecurityValidator.CreateNonce();
            UserService.AddUserConnect(ID, ip, challenge);
            Common.Log.Debug(ID + " " + iPEndPoint.ToString() + " Conection Open");
            //连接时回复公钥，不加密
            Message response = new()
            {
                Type = "publickey",
                Data = new
                {
                    publicKey = RsaService.GetRsaPublickKey(),
                    authChallenge = challenge,
                    authTtlSeconds = config.ChallengeTtlSeconds,
                    certFingerprintSha256 = SecurityRuntime.ServerCertificateFingerprintSha256
                },
            };
            string jsonString = response.ToJsonString();
            Send(jsonString);
        }

        protected override void OnClose(CloseEventArgs e)
        {
            UserService.RemoveUserConnectByID(ID);
            Common.Log.Debug(this.ID + " " + this.iPEndPoint.ToString() + " Conection Close" + e.Reason);
        }

        protected override void OnError(ErrorEventArgs e)
        {
            UserService.RemoveUserConnectByID(ID);
            Common.Log.Debug(this.ID + " " + this.iPEndPoint.ToString() + " Conection Error Close" + e.Message);
        }

        private static bool IsIpBlocked(string ip)
        {
            lock (_abuseLock)
            {
                if (!_ipBlockedUntil.TryGetValue(ip, out DateTime untilUtc))
                {
                    return false;
                }

                if (untilUtc <= DateTime.UtcNow)
                {
                    _ipBlockedUntil.Remove(ip);
                    return false;
                }

                return true;
            }
        }

        private static void BlockIp(string ip, int seconds)
        {
            lock (_abuseLock)
            {
                _ipBlockedUntil[ip] = DateTime.UtcNow.AddSeconds(seconds);
            }
        }
    }
}
