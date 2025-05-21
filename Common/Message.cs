using System.Collections;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Common
{
    class Message
    {
        /// <summary>
        /// 携带公钥，用于将服务器公钥传递给客户端
        /// </summary>
        public string? PublicKey { get; set; }

        /// <summary>
        /// 消息接收对象公钥
        /// </summary>
        public string? To { get; set; }

        /// <summary>
        /// 消息发送对象公钥
        /// </summary>
        public string? From { get; set; }

        /// <summary>
        /// 服务器要处理的消息
        /// </summary>
        public string? Data { get; set; }

        public static readonly JsonSerializerOptions options = new()
        {
            ReadCommentHandling = JsonCommentHandling.Skip, //允许注释
            AllowTrailingCommas = true,//允许尾随逗号
            // PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull, // 忽略 null 值
            WriteIndented = true, // 美化输出
            PropertyNameCaseInsensitive = true,//属性名忽略大小写
        };

        public static Message? JsonStringParse(string jsonString)
        {
            try
            {
                return JsonSerializer.Deserialize<Message>(jsonString, options);
            }
            catch (Exception ex)
            {
                Log.Exception(ex.Message);
                return null;
            }
        }

        public string ToJsonString()
        {
            return JsonSerializer.Serialize(this, options);
        }

    }
}