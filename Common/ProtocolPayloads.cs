using System.Text.Json;

namespace OnlineMsgServer.Common
{
    internal sealed class ClientRegistrationPayload
    {
        public string PublicKey { get; init; } = "";
        public string Challenge { get; init; } = "";
        public long Timestamp { get; init; }
        public string Nonce { get; init; } = "";
        public string Signature { get; init; } = "";

        public static bool TryParse(dynamic? data, out ClientRegistrationPayload payload, out string error)
        {
            payload = new ClientRegistrationPayload();
            error = "invalid data";

            if (data is not JsonElement json || json.ValueKind != JsonValueKind.Object)
            {
                error = "data must be object";
                return false;
            }

            if (!TryReadString(json, "publicKey", out string publicKey) ||
                !TryReadString(json, "challenge", out string challenge) ||
                !TryReadInt64(json, "timestamp", out long timestamp) ||
                !TryReadString(json, "nonce", out string nonce) ||
                !TryReadString(json, "signature", out string signature))
            {
                error = "missing registration fields";
                return false;
            }

            payload = new ClientRegistrationPayload
            {
                PublicKey = publicKey,
                Challenge = challenge,
                Timestamp = timestamp,
                Nonce = nonce,
                Signature = signature
            };
            return true;
        }

        public static string BuildSigningInput(string userName, string publicKey, string challenge, long timestamp, string nonce)
        {
            return string.Join("\n", "publickey", userName, publicKey, challenge, timestamp, nonce);
        }

        private static bool TryReadString(JsonElement root, string property, out string value)
        {
            value = "";
            if (!root.TryGetProperty(property, out JsonElement element) || element.ValueKind != JsonValueKind.String)
            {
                return false;
            }

            string? src = element.GetString();
            if (string.IsNullOrWhiteSpace(src))
            {
                return false;
            }
            value = src.Trim();
            return true;
        }

        private static bool TryReadInt64(JsonElement root, string property, out long value)
        {
            value = 0;
            if (!root.TryGetProperty(property, out JsonElement element))
            {
                return false;
            }
            return element.TryGetInt64(out value);
        }
    }

    internal sealed class SignedMessagePayload
    {
        public string Payload { get; init; } = "";
        public long Timestamp { get; init; }
        public string Nonce { get; init; } = "";
        public string Signature { get; init; } = "";

        public static bool TryParse(dynamic? data, out SignedMessagePayload payload, out string error)
        {
            payload = new SignedMessagePayload();
            error = "invalid data";

            if (data is not JsonElement json || json.ValueKind != JsonValueKind.Object)
            {
                error = "data must be object";
                return false;
            }

            if (!TryReadString(json, "payload", out string messagePayload) ||
                !TryReadInt64(json, "timestamp", out long timestamp) ||
                !TryReadString(json, "nonce", out string nonce) ||
                !TryReadString(json, "signature", out string signature))
            {
                error = "missing signed message fields";
                return false;
            }

            payload = new SignedMessagePayload
            {
                Payload = messagePayload,
                Timestamp = timestamp,
                Nonce = nonce,
                Signature = signature
            };
            return true;
        }

        public static string BuildSigningInput(string type, string key, string payload, long timestamp, string nonce)
        {
            return string.Join("\n", type, key, payload, timestamp, nonce);
        }

        private static bool TryReadString(JsonElement root, string property, out string value)
        {
            value = "";
            if (!root.TryGetProperty(property, out JsonElement element) || element.ValueKind != JsonValueKind.String)
            {
                return false;
            }

            string? src = element.GetString();
            if (string.IsNullOrWhiteSpace(src))
            {
                return false;
            }
            value = src;
            return true;
        }

        private static bool TryReadInt64(JsonElement root, string property, out long value)
        {
            value = 0;
            if (!root.TryGetProperty(property, out JsonElement element))
            {
                return false;
            }
            return element.TryGetInt64(out value);
        }
    }
}
