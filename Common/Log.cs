namespace OnlineMsgServer.Common
{
    class Log
    {
        public static void Normal(string msg)
        {
            DateTime now = DateTime.Now;
            string time = now.ToString("yyyy-MM-dd HH:mm:ss");
            Console.WriteLine($"[{time}][Normal] {msg}");
        }

        public static void Exception(string msg)
        {
            Normal("[Exception] " + msg);
        }
    }
}