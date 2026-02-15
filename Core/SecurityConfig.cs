namespace OnlineMsgServer.Core
{
    internal sealed class SecurityConfig
    {
        public int ListenPort { get; init; } = 13173;
        public bool RequireWss { get; init; } = false;
        public string? TlsCertPath { get; init; }
        public string? TlsCertPassword { get; init; }

        public string? ServerPrivateKeyBase64 { get; init; }
        public string? ServerPrivateKeyPath { get; init; }
        public bool AllowEphemeralServerKey { get; init; }

        public int MaxConnections { get; init; } = 1000;
        public int MaxMessageBytes { get; init; } = 64 * 1024;
        public int RateLimitCount { get; init; } = 30;
        public int RateLimitWindowSeconds { get; init; } = 10;
        public int IpBlockSeconds { get; init; } = 120;

        public int ChallengeTtlSeconds { get; init; } = 120;
        public int MaxClockSkewSeconds { get; init; } = 60;
        public int ReplayWindowSeconds { get; init; } = 120;

        public static SecurityConfig LoadFromEnvironment()
        {
            return new SecurityConfig
            {
                ListenPort = GetInt("LISTEN_PORT", 13173, 1),
                RequireWss = GetBool("REQUIRE_WSS", false),
                TlsCertPath = GetString("TLS_CERT_PATH"),
                TlsCertPassword = GetString("TLS_CERT_PASSWORD"),
                ServerPrivateKeyBase64 = GetString("SERVER_PRIVATE_KEY_B64"),
                ServerPrivateKeyPath = GetString("SERVER_PRIVATE_KEY_PATH"),
                AllowEphemeralServerKey = GetBool("ALLOW_EPHEMERAL_SERVER_KEY", false),
                MaxConnections = GetInt("MAX_CONNECTIONS", 1000, 1),
                MaxMessageBytes = GetInt("MAX_MESSAGE_BYTES", 64 * 1024, 512),
                RateLimitCount = GetInt("RATE_LIMIT_COUNT", 30, 1),
                RateLimitWindowSeconds = GetInt("RATE_LIMIT_WINDOW_SECONDS", 10, 1),
                IpBlockSeconds = GetInt("IP_BLOCK_SECONDS", 120, 1),
                ChallengeTtlSeconds = GetInt("CHALLENGE_TTL_SECONDS", 120, 10),
                MaxClockSkewSeconds = GetInt("MAX_CLOCK_SKEW_SECONDS", 60, 1),
                ReplayWindowSeconds = GetInt("REPLAY_WINDOW_SECONDS", 120, 10),
            };
        }

        private static string? GetString(string key)
        {
            string? value = Environment.GetEnvironmentVariable(key);
            if (string.IsNullOrWhiteSpace(value))
            {
                return null;
            }
            return value.Trim();
        }

        private static bool GetBool(string key, bool defaultValue)
        {
            string? value = Environment.GetEnvironmentVariable(key);
            if (string.IsNullOrWhiteSpace(value))
            {
                return defaultValue;
            }

            if (bool.TryParse(value, out bool parsed))
            {
                return parsed;
            }

            return value.Trim() switch
            {
                "1" => true,
                "0" => false,
                _ => defaultValue
            };
        }

        private static int GetInt(string key, int defaultValue, int minValue)
        {
            string? value = Environment.GetEnvironmentVariable(key);
            if (string.IsNullOrWhiteSpace(value))
            {
                return defaultValue;
            }

            if (!int.TryParse(value, out int parsed))
            {
                return defaultValue;
            }

            return Math.Max(parsed, minValue);
        }
    }
}
