using System.Diagnostics;
using System.Text;
using OnlineMsgServer.Common;
using WebSocketSharp;
using WebSocketSharp.Server;

namespace OnlineMsgServer.Core
{
    class MsgService
    {
        /// <summary>
        /// 增加计时逻辑 for debug
        /// </summary>
        public static async Task HandlerAndMeasure(string wsid, WebSocketSessionManager Sessions, Message message)
        {
            Stopwatch stopWatch = new();
            stopWatch.Start();
            await Handler(wsid, Sessions, message);
            stopWatch.Stop();
            Log.Normal($"处理Message耗时：{stopWatch.ElapsedMilliseconds}ms");
        }

        /// <summary>
        /// 指令处理逻辑(包括加密及发送过程)
        /// </summary>
        public static Task Handler(string wsid, WebSocketSessionManager Sessions, Message message)
        {
            return Task.Run(() =>
            {
                try
                {
                    //客户端登记公钥
                    if (message.PublicKey != null)
                    {
                        if (UserService.UserLogin(wsid, message.PublicKey))
                        {
                            //登记成功后，发送登记成功消息
                            Message response = new()
                            {
                                From = RsaService.GetServerRsaPublickKey(),
                                Data = Convert.ToBase64String(Encoding.UTF8.GetBytes("登记成功")),
                            };
                            string jsonString = response.ToJsonString();
                            string encryptString = RsaService.EncryptForClient(message.PublicKey, jsonString);
                            Sessions.Sessions.First(s => s.ID == wsid)?.Context.WebSocket.Send(encryptString);
                            Log.Normal(wsid + " " + message.PublicKey + " 登记成功");
                        }
                        else
                        {
                            Sessions.Sessions.First(s => s.ID == wsid)?.Context.WebSocket.Close(CloseStatusCode.Normal, "公钥登记失败");
                            Log.Normal(wsid + " " + message.PublicKey + " 登记失败，检测到重复公钥");
                        }
                        return;
                    }
                    if (message.Data == null)
                    {
                        throw new Exception("消息数据为空，放弃处理");
                    }
                    else
                    {
                        Message response = new()
                        {
                            //当用户发送完消息后马上关闭ws连接，服务器处理关闭连接在前的话，From可能获取到空值
                            //作为Feature存在 O(∩_∩)O
                            From = UserService.GetUserPublicKeyByID(wsid),
                            Data = message.Data,
                        };

                        if (message.To == null)
                        {
                            //消息接收对象为空，视为广播
                            foreach (IWebSocketSession session in Sessions.Sessions)
                            {
                                if (session.ID != wsid)//不用发给自己
                                {
                                    string? publicKey = UserService.GetUserPublicKeyByID(session.ID);
                                    if (publicKey != null)
                                    {
                                        string jsonString = response.ToJsonString();
                                        string encryptString = RsaService.EncryptForClient(publicKey, jsonString);
                                        session.Context.WebSocket.Send(encryptString);
                                    }
                                }
                            }
                        }
                        else
                        {
                            //消息接收对象指定，视为转发
                            User? user = UserService.GetUserListByPublicKey(message.To);
                            if (user != null)
                            {
                                foreach (IWebSocketSession session in Sessions.Sessions)
                                {
                                    if (user.ID == session.ID)
                                    {
                                        string jsonString = response.ToJsonString();
                                        string encryptString = RsaService.EncryptForClient(message.To, jsonString);
                                        session.Context.WebSocket.Send(encryptString);
                                        break;
                                    }
                                }
                            }
                            else
                            {
                                //用户不在线，发送错误消息
                                Message errorResponse = new()
                                {
                                    From = RsaService.GetServerRsaPublickKey(),
                                    Data = Convert.ToBase64String(Encoding.UTF8.GetBytes("用户不在线")),
                                };
                                string jsonString = errorResponse.ToJsonString();
                                string encryptString = RsaService.EncryptForClient(message.From!, jsonString);
                                Sessions.Sessions.First(s => s.ID == wsid)?.Context.WebSocket.Send(encryptString);
                                Log.Normal(wsid + " " + message.To + " 用户不在线");
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log.Exception(ex.Message);
                }
            });
        }
    }
}