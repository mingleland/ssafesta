using System;
using System.Security.Cryptography;
using System.Text;
using Festa.Network;
using NUnit.Framework;

namespace Festa.Tests
{
    /// <summary>
    /// 월드 입장 grant 검증 (S15P21A604-85, spec 002 FR-013·014).
    ///
    /// **토큰은 여기서 직접 조립한다 — `WorldEntryTokenSigner` 를 쓰지 않는다.**
    /// 내 서명기로 만들어 내 검증기로 통과시키면, 둘이 똑같이 틀려도 초록이 뜬다.
    /// Backend 는 Nimbus(RFC 7515 준수)로 발급하므로, 테스트도 RFC 7515 compact JWS 를
    /// `System.Security.Cryptography` 로 그대로 조립해서 **표준 호환성**을 겨눈다.
    ///
    /// 값들은 Backend `WorldEntryTokenIssuer`·`WorldProperties` 의 고정값과 같아야 한다.
    /// 여기가 어긋나면 실서버 grant 가 통째로 거부된다.
    /// </summary>
    public class WorldEntryTokenTests
    {
        const string Issuer = "ssafesta-backend";
        const string Audience = "ssafesta-world";
        const string WorldId = "11F";
        const string ChannelId = "11F-01";

        static readonly byte[] Key = Encoding.UTF8.GetBytes("test-key-that-is-at-least-32-bytes-long!!");

        // ---------- 통과해야 하는 것 ----------

        [Test]
        public void 백엔드가_발급한_모양의_grant_는_통과하고_클레임이_그대로_나온다()
        {
            var token = Jws(Claims(jti: "grant-1", playerId: "42", nickname: "형순",
                                   avatarCode: "sk_07"));

            Assert.IsTrue(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out var claims, out var reason),
                          $"통과해야 하는데 거부됐다: {reason}");

            Assert.AreEqual("grant-1", claims.Jti);
            Assert.AreEqual("42", claims.PlayerId);
            Assert.AreEqual("형순", claims.Nickname);      // 한글 닉네임이 UTF-8 왕복을 견디는지
            Assert.AreEqual("sk_07", claims.AvatarCode);
            Assert.AreEqual(ChannelId, claims.ChannelId);
        }

        [Test]
        public void avatarCode_클레임이_없으면_빈문자열이_아니라_null_이다()
        {
            // 게스트·미저장 회원은 Backend 가 이 클레임을 아예 빼고 발급한다. 빈 문자열을
            // 외형 코드로 넘기면 디코더가 실패하므로 null 이어야 기본값 폴백이 걸린다.
            var token = Jws(Claims(jti: "grant-guest", playerId: "0", nickname: "guest",
                                   avatarCode: null));

            Assert.IsTrue(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out var claims, out _));
            Assert.IsNull(claims.AvatarCode);
        }

        [Test]
        public void 신원은_클레임에서_나온다_클라이언트가_보낸_값이_아니다()
        {
            // 헌법 16조. Backend 발급 코드 주석이 게임 서버에게 명시적으로 지시하는 항목이다.
            var token = Jws(Claims(jti: "grant-id", playerId: "7", nickname: "진짜",
                                   avatarCode: "sk_02"));
            Assert.IsTrue(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out var claims, out _));

            var payload = claims.ToPayload(token);
            Assert.AreEqual(7L, payload.userId);
            Assert.AreEqual("진짜", payload.nickname);
            Assert.AreEqual("sk_02", payload.avatarCode);
        }

        // ---------- 거부해야 하는 것 ----------

        [Test]
        public void 서명이_다른_키로_된_grant_는_거부한다()
        {
            var forged = Jws(Claims(jti: "forged"),
                             Encoding.UTF8.GetBytes("another-key-that-is-also-32-bytes-long!!!"));

            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(forged, Key, out _, out var reason));
            StringAssert.Contains("서명", reason);
        }

        [Test]
        public void payload_를_고치면_서명이_깨져_거부한다()
        {
            // 서명 대상은 base64url 원문이다. 클레임만 바꿔치기하면 반드시 걸려야 한다.
            var honest = Jws(Claims(jti: "tamper", playerId: "1", nickname: "일반"));
            var parts = honest.Split('.');
            var swapped = B64(Encoding.UTF8.GetBytes(Claims(jti: "tamper", playerId: "999",
                                                            nickname: "관리자", role: "ADMIN")));

            var tampered = parts[0] + "." + swapped + "." + parts[2];

            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(tampered, Key, out _, out var reason));
            StringAssert.Contains("서명", reason);
        }

        [Test]
        public void alg_none_토큰은_거부한다()
        {
            // 서명을 아예 검사하지 않는 고전적 우회. 헤더의 alg 를 확인하지 않으면 통과한다.
            var header = B64(Encoding.UTF8.GetBytes("{\"alg\":\"none\",\"typ\":\"JWT\"}"));
            var body = B64(Encoding.UTF8.GetBytes(Claims(jti: "none-alg")));

            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(header + "." + body + ".", Key, out _, out _));
        }

        [Test]
        public void 만료된_grant_는_거부한다()
        {
            var token = Jws(Claims(jti: "expired", secondsFromNow: -3600));

            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("만료", reason);
        }

        [Test]
        public void 발급자가_다르면_거부한다()
        {
            var token = Jws(Claims(jti: "bad-iss", issuer: "someone-else"));
            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("iss", reason);
        }

        [Test]
        public void 대상이_다르면_거부한다()
        {
            // Access Token 을 그대로 들고 와도 통하지 않아야 한다 — aud 가 다르다.
            var token = Jws(Claims(jti: "bad-aud", audience: "ssafesta-api"));
            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("aud", reason);
        }

        [Test]
        public void 단일_문자열_aud_grant_를_수락한다()
        {
            // 실제 백엔드(Spring)는 aud 를 배열이 아니라 **단일 문자열**로 보낸다 —
            // RFC 7519 §4.1.3 이 허용하는 형태다. Claims.aud 가 string[] 라 JsonUtility 가
            // 문자열형을 조용히 null 로 두어 접속이 전부 거부되던 결함의 회귀 방어다
            // (S15P21A604-340 — 목 서버·테스트가 배열형만 만들어 실측 전까지 못 잡았다).
            var token = Jws(Claims(jti: "scalar-aud", scalarAud: true));
            Assert.IsTrue(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out var entry, out var reason),
                          $"실제 백엔드 형태의 grant 가 거부됐다: {reason}");
            Assert.AreEqual("1", entry.PlayerId);
        }

        [Test]
        public void 단일_문자열_aud_라도_대상이_다르면_거부한다()
        {
            // 형태를 받아 주는 것이지 검증을 약화하는 것이 아니다.
            var token = Jws(Claims(jti: "scalar-bad-aud", audience: "ssafesta-api", scalarAud: true));
            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("aud", reason);
        }

        [Test]
        public void 정규화는_aud_밖의_문자열을_건드리지_않는다()
        {
            // NormalizeAudience 는 문자열형 aud 만 감싼다 — 배열형·이스케이프가 있는 값·
            // aud 가 없는 payload 는 원문 그대로여야 한다.
            Assert.AreEqual("{\"aud\":[\"x\"]}", WorldEntryTokenVerifier.NormalizeAudience("{\"aud\":[\"x\"]}"));
            Assert.AreEqual("{\"iss\":\"a\"}", WorldEntryTokenVerifier.NormalizeAudience("{\"iss\":\"a\"}"));
            Assert.AreEqual("{\"aud\":[\"x\\\"y\"],\"iss\":\"a\"}",
                            WorldEntryTokenVerifier.NormalizeAudience("{\"aud\":\"x\\\"y\",\"iss\":\"a\"}"));
        }

        [Test]
        public void 다른_채널의_grant_는_거부한다()
        {
            // Channel 이 늘어나면 11F-02 용 grant 로 11F-01 에 들어오는 걸 막아야 한다.
            var token = Jws(Claims(jti: "bad-channel", channelId: "11F-02"));
            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("channelId", reason);
        }

        [Test]
        public void jti_가_없으면_거부한다()
        {
            // jti 가 없으면 재사용을 막을 방법이 없다 (FR-014).
            var token = Jws(Claims(jti: null));
            Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(token, Key, out _, out var reason));
            StringAssert.Contains("jti", reason);
        }

        [Test]
        public void JWT_형식이_아니면_거부한다()
        {
            // POC 승인 규칙("비어 있지 않으면 통과")이 통과시키던 값들이다.
            foreach (var junk in new[] { "loadtest", "mock-connection-token", "a.b", "" })
                Assert.IsFalse(WorldEntryTokenVerifier.VerifyWithKey(junk, Key, out _, out _),
                               $"'{junk}' 가 통과했다 — POC 스텁이 살아 있다");
        }

        // ---------- 재사용 차단 (FR-014) ----------

        [Test]
        public void 같은_grant_를_두_번_쓰면_두_번째는_거부한다()
        {
            // 서명이 유효해도 한 번만 쓸 수 있어야 한다. 원장은 파일이라 재배포도 넘긴다.
            var jti = "replay-" + Guid.NewGuid().ToString("N");
            var exp = WorldEntryToken.UnixNow() + 120;

            Assert.IsTrue(GrantReplayLedger.TryConsume(jti, exp, out var first), $"첫 소비 실패: {first}");
            Assert.IsFalse(GrantReplayLedger.TryConsume(jti, exp, out var second), "재사용이 통과했다");
            StringAssert.Contains("사용된", second);
        }

        // ---------- 조립 도구 ----------

        /// <summary>RFC 7515 compact JWS 를 표준대로 조립한다 (Backend 의 Nimbus 와 같은 규격).</summary>
        static string Jws(string claimsJson, byte[] key = null)
        {
            var signingInput = B64(Encoding.UTF8.GetBytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"))
                             + "." + B64(Encoding.UTF8.GetBytes(claimsJson));
            using var hmac = new HMACSHA256(key ?? Key);
            return signingInput + "." + B64(hmac.ComputeHash(Encoding.ASCII.GetBytes(signingInput)));
        }

        /// <summary>base64url — padding 없음, `+/` 대신 `-_`.</summary>
        static string B64(byte[] bytes) =>
            Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        static string Claims(string jti, string playerId = "1", string nickname = "tester",
                             string avatarCode = "sk_01", string role = "MEMBER",
                             string issuer = Issuer, string audience = Audience,
                             string worldId = WorldId, string channelId = ChannelId,
                             long secondsFromNow = 120, bool scalarAud = false)
        {
            long now = WorldEntryToken.UnixNow();
            var sb = new StringBuilder();
            sb.Append("{\"iss\":\"").Append(issuer).Append("\",");
            // RFC 7519 §4.1.3 — aud 는 배열·단일 문자열 둘 다 유효하다.
            // 실제 백엔드(Spring)는 단일 문자열로 보낸다 (S15P21A604-340).
            if (scalarAud) sb.Append("\"aud\":\"").Append(audience).Append("\",");
            else sb.Append("\"aud\":[\"").Append(audience).Append("\"],");
            sb.Append("\"sub\":\"").Append(playerId).Append("\",");
            if (jti != null) sb.Append("\"jti\":\"").Append(jti).Append("\",");
            sb.Append("\"iat\":").Append(now).Append(',');
            sb.Append("\"exp\":").Append(now + secondsFromNow).Append(',');
            sb.Append("\"role\":\"").Append(role).Append("\",");
            sb.Append("\"playerId\":\"").Append(playerId).Append("\",");
            sb.Append("\"nickname\":\"").Append(nickname).Append("\",");
            sb.Append("\"sessionId\":\"session-1\",");
            sb.Append("\"worldId\":\"").Append(worldId).Append("\",");
            sb.Append("\"channelId\":\"").Append(channelId).Append('"');
            // avatarCode 가 null 이면 클레임 자체를 넣지 않는다 — Backend 와 같은 동작이다.
            if (avatarCode != null) sb.Append(",\"avatarCode\":\"").Append(avatarCode).Append('"');
            sb.Append('}');
            return sb.ToString();
        }
    }
}
