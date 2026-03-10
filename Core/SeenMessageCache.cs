using System.Security.Cryptography;
using System.Text;

namespace OnlineMsgServer.Core
{
    internal sealed class SeenMessageCache
    {
        private readonly object _lock = new();
        private readonly Dictionary<string, DateTime> _seenUntilUtc = [];
        private readonly int _ttlSeconds;

        public SeenMessageCache(int ttlSeconds)
        {
            _ttlSeconds = Math.Max(ttlSeconds, 1);
        }

        public bool TryMark(string senderIdentity, string type, string key, string payload)
        {
            string hash = ComputeHash(senderIdentity, type, key, payload);
            DateTime nowUtc = DateTime.UtcNow;

            lock (_lock)
            {
                if (_seenUntilUtc.TryGetValue(hash, out DateTime untilUtc) && untilUtc > nowUtc)
                {
                    return false;
                }

                _seenUntilUtc[hash] = nowUtc.AddSeconds(_ttlSeconds);

                List<string> expiredKeys = [];
                foreach (KeyValuePair<string, DateTime> item in _seenUntilUtc)
                {
                    if (item.Value <= nowUtc)
                    {
                        expiredKeys.Add(item.Key);
                    }
                }

                foreach (string expiredKey in expiredKeys)
                {
                    _seenUntilUtc.Remove(expiredKey);
                }

                return true;
            }
        }

        private static string ComputeHash(string senderIdentity, string type, string key, string payload)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(string.Join("\n", senderIdentity, type, key, payload));
            return Convert.ToHexString(SHA256.HashData(bytes));
        }
    }
}
