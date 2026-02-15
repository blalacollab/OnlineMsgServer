namespace OnlineMsgServer.Core
{
    internal static class SecurityRuntime
    {
        private static readonly object _lock = new();

        public static SecurityConfig Config { get; private set; } = SecurityConfig.LoadFromEnvironment();
        public static string? ServerCertificateFingerprintSha256 { get; private set; }

        public static void Initialize(SecurityConfig config, string? certFingerprintSha256)
        {
            lock (_lock)
            {
                Config = config;
                ServerCertificateFingerprintSha256 = certFingerprintSha256;
            }
        }
    }
}
