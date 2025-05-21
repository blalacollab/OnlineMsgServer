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
        protected override async void OnMessage(MessageEventArgs e)
        {
            try
            {
                Log.Debug(ID + " " + Context.UserEndPoint.ToString() + ":" + e.Data);
                //从base64字符串解密
                string decryptString = RsaService.Decrypt(e.Data);
                //json 反序列化
                Message? message = Message.JsonStringParse(decryptString);
                if (message != null)
                {
#if DEBUG
                    await MsgService.HandlerAndMeasure(ID, Sessions, message);
#else
                    await MsgService.Handler(ID, Sessions, message);
#endif
                }
            }
            catch (Exception ex)
            {
                Common.Log.Exception("Error processing message: " + ex.Message);
            }
        }

        protected override void OnOpen()
        {
            iPEndPoint = Context.UserEndPoint;
            UserService.AddUserConnect(ID);
            Common.Log.Normal(ID + " " + iPEndPoint.ToString() + " Conection Open");
            //连接时回复公钥，不加密
            Message response = new()
            {
                PublicKey = RsaService.GetServerRsaPublickKey(),
            };
            string jsonString = response.ToJsonString();
            Send(jsonString);
        }

        protected override void OnClose(CloseEventArgs e)
        {
            UserService.RemoveUserConnectByID(ID);
            Common.Log.Normal(this.ID + " " + this.iPEndPoint.ToString() + " Conection Close" + e.Reason);
        }

        protected override void OnError(ErrorEventArgs e)
        {
            UserService.RemoveUserConnectByID(ID);
            Common.Log.Normal(this.ID + " " + this.iPEndPoint.ToString() + " Conection Error Close" + e.Message);
        }
    }
}