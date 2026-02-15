using System.Security.Cryptography;
using System.Text;

namespace OnlineMsgServer.Core
{
    class RsaService
    {
        //用于服务端加密解密
        private static readonly RSA _Rsa = RSA.Create();
        private static readonly object _RsaLock = new();

        //用于客户端加密
        private static readonly RSA _PublicRsa = RSA.Create();
        private static readonly object _PublicRsaLock = new();

        /// <summary>
        /// 用客户端公钥加密
        /// </summary>
        public static string EncryptForClient(string pkey, string msg)
        {
            lock (_PublicRsaLock)
            {
                _PublicRsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(pkey), out _);
                // byte[] encrypt = _PublicRsa.Encrypt(Encoding.UTF8.GetBytes(msg), RSAEncryptionPadding.OaepSHA256);
                // return Convert.ToBase64String(encrypt);
                return RsaEncrypt(_PublicRsa, msg);
            }
        }

        /// <summary>
        /// 导入服务端私钥
        /// </summary>
        public static void LoadRsaPkey(SecurityConfig config)
        {
            lock (_RsaLock)
            {
                if (!string.IsNullOrWhiteSpace(config.ServerPrivateKeyBase64))
                {
                    _Rsa.ImportPkcs8PrivateKey(Convert.FromBase64String(config.ServerPrivateKeyBase64), out _);
                    return;
                }

                if (!string.IsNullOrWhiteSpace(config.ServerPrivateKeyPath))
                {
                    string pkey = File.ReadAllText(config.ServerPrivateKeyPath).Trim();
                    _Rsa.ImportPkcs8PrivateKey(Convert.FromBase64String(pkey), out _);
                    return;
                }

                if (config.AllowEphemeralServerKey)
                {
                    OnlineMsgServer.Common.Log.Security("server_key_ephemeral", "using in-memory generated private key");
                    return;
                }

                throw new InvalidOperationException("服务端私钥未配置。请设置 SERVER_PRIVATE_KEY_B64 或 SERVER_PRIVATE_KEY_PATH。");
            }
        }

        /// <summary>
        /// 以base64格式导出公钥字符串
        /// </summary>
        /// <returns>公钥字符串，base64格式</returns>
        public static string GetRsaPublickKey()
        {
            lock (_RsaLock)
            {
                return Convert.ToBase64String(_Rsa.ExportSubjectPublicKeyInfo());
            }
        }

        /// <summary>
        /// 服务端解密 base64编码
        /// </summary>
        /// <param name="secret">密文</param>
        /// <returns> 原文字符串</returns>
        public static string Decrypt(string secret)
        {
            lock (_RsaLock)
            {
                byte[] secretBytes = Convert.FromBase64String(secret);
                int size = secretBytes.Length;
                int blockSize = 256;
                if (size % blockSize != 0)
                {
                    throw new FormatException("ciphertext length invalid");
                }
                int blockCount = size / blockSize;
                List<byte> decryptList = [];
                for (int i = 0; i < blockCount; i++)
                {
                    byte[] block = new byte[blockSize];
                    Array.Copy(secretBytes, i * blockSize, block, 0, blockSize);
                    byte[] decryptBlock = _Rsa.Decrypt(block, RSAEncryptionPadding.OaepSHA256);
                    decryptList.AddRange(decryptBlock);
                }

                // byte[] decrypt = _Rsa.Decrypt(Convert.FromBase64String(base64),
                //                     RSAEncryptionPadding.OaepSHA256);
                return Encoding.UTF8.GetString([.. decryptList]);
            }
        }

        /// <summary>
        /// 服务端加密 base64编码
        /// </summary>
        /// <param name="src">原文字符串</param>
        /// <returns>密文</returns>
        public static string Encrypt(string src)
        {
            lock (_RsaLock)
            {
                return RsaEncrypt(_Rsa, src);
            }
        }

        public static bool VerifySignature(string publicKeyBase64, string src, string signatureBase64)
        {
            lock (_PublicRsaLock)
            {
                try
                {
                    _PublicRsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeyBase64), out _);
                    byte[] srcBytes = Encoding.UTF8.GetBytes(src);
                    byte[] signatureBytes = Convert.FromBase64String(signatureBase64);
                    return _PublicRsa.VerifyData(srcBytes, signatureBytes, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                }
                catch
                {
                    return false;
                }
            }
        }

        public static bool IsPublicKeyValid(string publicKeyBase64)
        {
            lock (_PublicRsaLock)
            {
                try
                {
                    _PublicRsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeyBase64), out _);
                    return true;
                }
                catch
                {
                    return false;
                }
            }
        }

        private static string RsaEncrypt(RSA rsa, string src)
        {
            byte[] srcBytes = Encoding.UTF8.GetBytes(src);
            int size = srcBytes.Length;
            int blockSize = 190;
            if (size == 0)
            {
                return "";
            }

            int blockCount = (size + blockSize - 1) / blockSize;
            List<byte> encryptList = [];
            for (int i = 0; i < blockCount; i++)
            {
                int len = Math.Min(blockSize, size - i * blockSize);
                byte[] block = new byte[len];
                Array.Copy(srcBytes, i * blockSize, block, 0, len);
                byte[] encryptBlock = rsa.Encrypt(block, RSAEncryptionPadding.OaepSHA256);
                encryptList.AddRange(encryptBlock);
            }
            return Convert.ToBase64String([.. encryptList]);
        }
    }
}
