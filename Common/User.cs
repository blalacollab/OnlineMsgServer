namespace OnlineMsgServer.Common
{
    public class User(string ID)
    {
        /// <summary>
        /// ws连接生成的唯一uuid
        /// </summary>
        public string ID { get; set; } = ID;

        /// <summary>
        /// 用户名，在客户端随意指定
        /// </summary>
        public string? Name { get; set; }


        /// <summary>
        /// 用户公钥 用于消息加密发送给用户
        /// </summary>
        public string? PublicKey { get; set; }

        /// <summary>
        /// 是否通过鉴权
        /// </summary>
        public bool IsAuthenticated { get; set; }

        /// <summary>
        /// 连接来源IP
        /// </summary>
        public string? IpAddress { get; set; }

        /// <summary>
        /// 服务端下发的一次性 challenge
        /// </summary>
        public string? PendingChallenge { get; set; }

        /// <summary>
        /// challenge 下发时间（UTC）
        /// </summary>
        public DateTime ChallengeIssuedAtUtc { get; set; } = DateTime.UtcNow;

        /// <summary>
        /// 登录成功时间（UTC）
        /// </summary>
        public DateTime? AuthenticatedAtUtc { get; set; }

        /// <summary>
        /// 防重放 nonce 缓存（nonce -> unix timestamp）
        /// </summary>
        public Dictionary<string, long> ReplayNonceStore { get; } = [];

        /// <summary>
        /// 限流窗口内请求时间戳（unix ms）
        /// </summary>
        public Queue<long> RequestTimesMs { get; } = new();
    }
}
