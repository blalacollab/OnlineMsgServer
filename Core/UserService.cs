using OnlineMsgServer.Common;

namespace OnlineMsgServer.Core
{
    class UserService
    {
        #region 服务器用户管理
        private static readonly List<User> _UserList = [];
        private static readonly object _UserListLock = new();

        /// <summary>
        /// 通过wsid添加用户记录
        /// </summary>
        public static void AddUserConnect(string wsid, string ipAddress, string challenge)
        {
            lock (_UserListLock)
            {
                User user = new(wsid);
                user.IpAddress = ipAddress;
                user.PendingChallenge = challenge;
                user.ChallengeIssuedAtUtc = DateTime.UtcNow;
                _UserList.Add(user);
            }
        }
        /// <summary>
        /// 通过wsid移除用户记录
        /// </summary>
        /// <param name="wsid"></param>
        public static void RemoveUserConnectByID(string wsid)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user != null)
                {
                    _UserList.Remove(user);
                }
            }
        }

        /// <summary>
        /// 通过publickey返回用户列表
        /// </summary>
        public static List<User> GetUserListByPublicKey(string publicKey)
        {
            lock (_UserListLock)
            {
                return _UserList.FindAll(u => u.PublicKey == publicKey && u.IsAuthenticated);
            }
        }


        /// <summary>
        /// 通过wsid设置用户PublicKey
        /// </summary>
        public static void UserLogin(string wsid, string publickey, string name)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user != null)
                {
                    user.PublicKey = publickey.Trim();
                    user.Name = name.Trim();
                    user.IsAuthenticated = true;
                    user.PendingChallenge = null;
                    user.AuthenticatedAtUtc = DateTime.UtcNow;
                    Console.WriteLine(user.ID + " 登记成功");
                }
                else
                {
                    throw new Exception("用户不存在");
                }
            }
        }

        /// <summary>
        /// 通过wsid获取用户PublicKey
        /// </summary>
        public static string? GetUserPublicKeyByID(string wsid)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user is { IsAuthenticated: true })
                {
                    return user.PublicKey;
                }
                return null;
            }
        }

        /// <summary>
        /// 通过wsid获取UserName
        /// </summary>
        public static string? GetUserNameByID(string wsid)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user is { IsAuthenticated: true })
                {
                    return user.Name;
                }
                return null;
            }
        }

        /// <summary>
        /// 通过用户PublicKey获取wsid
        /// </summary>
        public static string? GetUserIDByPublicKey(string publicKey)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.PublicKey == publicKey && u.IsAuthenticated);
                if (user != null)
                {
                    return user.ID;
                }
                return null;
            }
        }

        public static bool IsAuthenticated(string wsid)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                return user is { IsAuthenticated: true };
            }
        }

        public static int GetConnectionCount()
        {
            lock (_UserListLock)
            {
                return _UserList.Count;
            }
        }

        public static bool TryGetChallenge(string wsid, out string challenge, out DateTime issuedAtUtc)
        {
            lock (_UserListLock)
            {
                challenge = "";
                issuedAtUtc = DateTime.MinValue;
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user == null || string.IsNullOrWhiteSpace(user.PendingChallenge))
                {
                    return false;
                }

                challenge = user.PendingChallenge;
                issuedAtUtc = user.ChallengeIssuedAtUtc;
                return true;
            }
        }

        public static bool TryRecordNonce(string wsid, string nonce, long timestamp, int replayWindowSeconds, out string reason)
        {
            lock (_UserListLock)
            {
                reason = "";
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user == null)
                {
                    reason = "user not found";
                    return false;
                }

                if (string.IsNullOrWhiteSpace(nonce))
                {
                    reason = "nonce empty";
                    return false;
                }

                long cutoff = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - replayWindowSeconds;
                List<string> expiredKeys = [];
                foreach (KeyValuePair<string, long> item in user.ReplayNonceStore)
                {
                    if (item.Value < cutoff)
                    {
                        expiredKeys.Add(item.Key);
                    }
                }

                foreach (string key in expiredKeys)
                {
                    user.ReplayNonceStore.Remove(key);
                }

                if (user.ReplayNonceStore.ContainsKey(nonce))
                {
                    reason = "replay nonce detected";
                    return false;
                }

                user.ReplayNonceStore[nonce] = timestamp;
                return true;
            }
        }

        public static bool IsRateLimitExceeded(string wsid, int maxRequests, int windowSeconds)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user == null)
                {
                    return true;
                }

                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                long cutoff = nowMs - (windowSeconds * 1000L);
                while (user.RequestTimesMs.Count > 0 && user.RequestTimesMs.Peek() < cutoff)
                {
                    user.RequestTimesMs.Dequeue();
                }

                if (user.RequestTimesMs.Count >= maxRequests)
                {
                    return true;
                }

                user.RequestTimesMs.Enqueue(nowMs);
                return false;
            }
        }

        #endregion
    }
}
