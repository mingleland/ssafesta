using System;
using System.Security.Cryptography;
using System.Text;

namespace Festa.Network
{
    /// <summary>
    /// 부하 테스트 전용 grant 발급기.
    ///
    /// **정상 경로에서는 쓰지 않는다.** 실제 grant 는 Backend `WorldEntryTokenIssuer` 만 만든다 —
    /// 신원을 계정 행에서 뽑아야 하기 때문이고(헌법 16조), 클라이언트가 자기 신원을 서명하면
    /// 검증의 의미가 사라진다.
    ///
    /// 그럼 왜 두는가. `S15P21A604-85` 로 입장 검증이 켜지면서 부하 봇이 보내던 고정 문자열
    /// `"loadtest"` 가 거부된다. 봇을 검증에서 빼주는 예외를 서버에 만들면 **그 예외가 곧
    /// 우회로**가 된다. 그러느니 봇이 서버와 같은 키로 진짜 grant 를 서명하게 한다 —
    /// 부하 테스트가 실제 승인 경로를 그대로 타므로 측정값도 더 정확해진다.
    ///
    /// 키가 없으면 발급하지 못한다. 그때는 봇도 붙지 못하며, 그게 옳다.
    /// </summary>
    public static class WorldEntryTokenSigner
    {
        const string Issuer = "ssafesta-backend";
        const string Audience = "ssafesta-world";
        const string WorldId = "11F";
        const string ChannelId = "11F-01";

        /// <summary>Backend TTL 과 같은 120초.</summary>
        const int TtlSeconds = 120;

        /// <summary>
        /// 부하 봇용 grant 를 만든다. <paramref name="jti"/> 는 호출자가 매번 다르게 줘야 한다 —
        /// 재사용 원장이 두 번째부터 거부한다.
        /// </summary>
        public static bool TryIssue(string jti, string playerId, string nickname,
                                    string avatarCode, out string token, out string failure)
        {
            token = null;

            if (!WorldEntryTokenSecret.TryGet(out var key, out failure))
                return false;

            long now = WorldEntryToken.UnixNow();

            var header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            var claims =
                "{\"iss\":\"" + Issuer + "\"," +
                "\"aud\":[\"" + Audience + "\"]," +
                "\"sub\":\"" + Escape(playerId) + "\"," +
                "\"jti\":\"" + Escape(jti) + "\"," +
                "\"iat\":" + now + "," +
                "\"exp\":" + (now + TtlSeconds) + "," +
                "\"role\":\"MEMBER\"," +
                "\"playerId\":\"" + Escape(playerId) + "\"," +
                "\"nickname\":\"" + Escape(nickname) + "\"," +
                "\"sessionId\":\"loadtest-" + Escape(jti) + "\"," +
                "\"worldId\":\"" + WorldId + "\"," +
                "\"channelId\":\"" + ChannelId + "\"," +
                "\"avatarCode\":\"" + Escape(avatarCode) + "\"}";

            var signingInput = Base64Url(Encoding.UTF8.GetBytes(header)) + "." +
                               Base64Url(Encoding.UTF8.GetBytes(claims));

            using var hmac = new HMACSHA256(key);
            var signature = hmac.ComputeHash(Encoding.ASCII.GetBytes(signingInput));

            token = signingInput + "." + Base64Url(signature);
            failure = null;
            return true;
        }

        static string Base64Url(byte[] bytes) =>
            Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        /// <summary>봇 이름에 따옴표가 섞여도 JSON 이 깨지지 않게 한다.</summary>
        static string Escape(string value) =>
            string.IsNullOrEmpty(value) ? "" : value.Replace("\\", "\\\\").Replace("\"", "\\\"");
    }
}
