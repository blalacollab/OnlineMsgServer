using System.Security.Cryptography;
using OnlineMsgServer.Common;

namespace OnlineMsgServer.Core
{
    internal static class SecurityValidator
    {
        public static string CreateNonce(int bytes = 24)
        {
            byte[] buffer = RandomNumberGenerator.GetBytes(bytes);
            return Convert.ToBase64String(buffer);
        }

        public static bool IsTimestampAcceptable(long unixSeconds, int maxClockSkewSeconds, int replayWindowSeconds)
        {
            long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            long maxFuture = now + maxClockSkewSeconds;
            long minPast = now - replayWindowSeconds;
            return unixSeconds >= minPast && unixSeconds <= maxFuture;
        }

        public static bool VerifySignedMessage(string wsid, string type, string key, SignedMessagePayload payload, out string reason)
        {
            reason = "";
            SecurityConfig config = SecurityRuntime.Config;

            if (!IsTimestampAcceptable(payload.Timestamp, config.MaxClockSkewSeconds, config.ReplayWindowSeconds))
            {
                reason = "timestamp out of accepted window";
                return false;
            }

            string? publicKey = UserService.GetUserPublicKeyByID(wsid);
            if (string.IsNullOrWhiteSpace(publicKey))
            {
                reason = "sender public key missing";
                return false;
            }

            string signingInput = SignedMessagePayload.BuildSigningInput(type, key, payload.Payload, payload.Timestamp, payload.Nonce);
            if (!RsaService.VerifySignature(publicKey, signingInput, payload.Signature))
            {
                reason = "signature verify failed";
                return false;
            }

            if (!UserService.TryRecordNonce(wsid, payload.Nonce, payload.Timestamp, config.ReplayWindowSeconds, out reason))
            {
                return false;
            }

            return true;
        }
    }
}
