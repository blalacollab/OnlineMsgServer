namespace OnlineMsgServer.Common
{
    class Log
    {
        public static void Debug(string msg)
        {
#if DEBUG
            Console.WriteLine(msg);
#endif
        }

        public static void Security(string eventName, string details)
        {
            Console.WriteLine($"[SECURITY] {DateTime.UtcNow:O} {eventName} {details}");
        }
    }
}
