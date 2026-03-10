using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using OnlineMsgServer.Common;
using OnlineMsgServer.Core;
using WebSocketSharp.Server;

namespace OnlineMsgServer
{
    class Program
    {
        static async Task Main(string[] args)
        {
            try
            {
                await MainLoop();
            }
            catch (Exception e)
            {
                Console.WriteLine(e.Message);
            }
        }

        static async Task MainLoop()
        {
            SecurityConfig config = SecurityConfig.LoadFromEnvironment();
            string? certFingerprint = null;

            //初始化RSA
            RsaService.LoadRsaPkey(config);

            var wssv = new WebSocketServer(config.ListenPort, config.RequireWss);
            if (config.RequireWss)
            {
                X509Certificate2 certificate = LoadTlsCertificate(config);
                wssv.SslConfiguration.ServerCertificate = certificate;
                wssv.SslConfiguration.EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13;
                certFingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData));
                Console.WriteLine($"TLS cert SHA256 fingerprint: {certFingerprint}");
            }
            else
            {
                Log.Security("transport_weak", "REQUIRE_WSS=false, service is running without TLS");
            }

            SecurityRuntime.Initialize(config, certFingerprint);

            //开启ws监听
            wssv.AddWebSocketService<WsService>("/");
            wssv.Start();
            PeerNetworkService.Initialize(config, wssv.WebSocketServices["/"].Sessions);
            PeerNetworkService.Start();
            Console.WriteLine("已开启ws监听, 端口: " + config.ListenPort);

            bool loopFlag = true;
            while (loopFlag)
            {
#if DEBUG
                Console.WriteLine("输入exit退出程序");
                string input = Console.ReadLine() ?? "";
                switch (input.Trim())
                {
                    case "exit":
                        loopFlag = false;
                        break;
                    case "port":
                        Console.WriteLine("服务器开放端口为" + config.ListenPort);
                        break;
                    default:
                        break;
                }
#endif
                await Task.Delay(5000);// 每5秒检查一次
            }
            PeerNetworkService.Stop();
            wssv.Stop();
        }

        static X509Certificate2 LoadTlsCertificate(SecurityConfig config)
        {
            if (string.IsNullOrWhiteSpace(config.TlsCertPath))
            {
                throw new InvalidOperationException("启用WSS时必须配置 TLS_CERT_PATH。");
            }

            if (!File.Exists(config.TlsCertPath))
            {
                throw new FileNotFoundException("找不到 TLS 证书文件。", config.TlsCertPath);
            }

            X509Certificate2 cert = string.IsNullOrEmpty(config.TlsCertPassword)
                ? new X509Certificate2(config.TlsCertPath)
                : new X509Certificate2(config.TlsCertPath, config.TlsCertPassword);

            if (!cert.HasPrivateKey)
            {
                throw new InvalidOperationException("TLS 证书缺少私钥，请使用包含私钥的 PFX 证书。");
            }

            return cert;
        }
    }
}
