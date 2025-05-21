using System.Net;

namespace OnlineMsgServer.Common
{
    public class User(string ID)
    {
        /// <summary>
        /// ws连接生成的唯一uuid
        /// </summary>
        public string ID { get; set; } = ID;

        /// <summary>
        /// 用户公钥
        /// </summary>
        public string? PublicKey { get; set; }
    }
}