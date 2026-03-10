using System.Text.Json;
using System.Text.Json.Serialization;

namespace OnlineMsgServer.Common
{
    internal sealed class PeerRelayEnvelope
    {
        public const string OverlayName = "oms-peer/1";

        public string Overlay { get; init; } = OverlayName;
        public string Kind { get; init; } = "";
        public string TargetKey { get; init; } = "";
        public string Payload { get; init; } = "";

        private static readonly JsonSerializerOptions Options = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };

        public string ToJsonString()
        {
            return JsonSerializer.Serialize(this, Options);
        }

        public static bool TryParse(string? jsonString, out PeerRelayEnvelope envelope)
        {
            envelope = new PeerRelayEnvelope();
            if (string.IsNullOrWhiteSpace(jsonString))
            {
                return false;
            }

            try
            {
                PeerRelayEnvelope? parsed = JsonSerializer.Deserialize<PeerRelayEnvelope>(jsonString, Options);
                if (parsed == null || !string.Equals(parsed.Overlay, OverlayName, StringComparison.Ordinal))
                {
                    return false;
                }

                if (string.IsNullOrWhiteSpace(parsed.Kind))
                {
                    return false;
                }

                envelope = parsed;
                return true;
            }
            catch
            {
                return false;
            }
        }
    }
}
