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
        public static void AddUserConnect(string wsid)
        {
            lock (_UserListLock)
            {
                User user = new(wsid);
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
        /// 通过publickey返回用户
        /// </summary>
        public static User? GetUserListByPublicKey(string publicKey)
        {
            lock (_UserListLock)
            {
                return _UserList.Find(u => u.PublicKey == publicKey);
            }
        }


        /// <summary>
        /// 通过wsid设置用户PublicKey
        /// </summary>
        public static bool UserLogin(string wsid, string publickey)
        {
            lock (_UserListLock)
            {
                if (IsPublicKeyExist(publickey))
                {
                    //假设用户通过[安全的渠道]获取他人公钥建立转发通道，即公钥不会被攻击者知晓
                    //这里设置不允许重复公钥登记
                    //如果攻击者从广播中或是其他渠道知晓用户公钥，在用户未退出连接前无法干扰用户发送消息
                    //所以客户端在进行广播前建议使用新生成公私钥，转发时使用另一套公私钥
                    return false;
                }
                User? user = _UserList.Find(u => u.ID == wsid);
                if (user != null)
                {
                    user.PublicKey = publickey;
                    return true;
                }
                else
                {
                    return false;
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
                if (user != null)
                {
                    return user.PublicKey;
                }
                return null;
            }
        }

        /// <summary>
        /// 查询PublicKey是否已登记
        /// </summary>
        public static bool IsPublicKeyExist(string publicKey)
        {
            lock (_UserListLock)
            {
                User? user = _UserList.Find(u => u.PublicKey == publicKey);
                if (user != null)
                {
                    return true;
                }
                return false;
            }
        }

        #endregion
    }
}