using System;
using System.Security.Cryptography;
using System.Text;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 월드 입장 grant(JWT, HS256)를 **Spring 을 부르지 않고** 자체 검증한다 (헌법 14조, spec 002 FR-013).
    ///
    /// Backend 는 Access Token 키(`JWT_SECRET`)와 분리된 전용 키로 이 grant 에 서명한다. 그 이유가
    /// `WorldEntryTokenIssuer` 주석에 적혀 있다 — 서명 키는 배포된 게임 컨테이너 안에 들어가므로,
    /// 그 키가 Access Token 키와 같다면 컨테이너 유출이 **모든 계정의 Access Token 위조**로 번진다.
    /// 전용 키면 최악이 월드 입장 위조로 한정된다.
    ///
    /// 검증 항목 — 하나라도 어긋나면 거부한다:
    /// 서명(HS256) · `iss` · `aud` · `exp` · `worldId` · `channelId` · `jti` 재사용 여부.
    /// </summary>
    public static class WorldEntryTokenVerifier
    {
        // Backend 고정값. WorldEntryTokenIssuer / WorldProperties 와 반드시 같아야 한다.
        const string ExpectedIssuer = "ssafesta-backend";
        const string ExpectedAudience = "ssafesta-world";
        const string ExpectedWorldId = "11F";
        const string ExpectedChannelId = "11F-01";

        /// <summary>
        /// 서버 간 시계 오차 허용치. grant TTL 이 120초라 넉넉하게 잡을 수 없다 —
        /// 크게 잡으면 만료된 grant 가 그만큼 더 통한다.
        /// </summary>
        const long ClockSkewSeconds = 5;

        /// <summary>
        /// grant 를 검증한다. 성공하면 <paramref name="token"/> 에 클레임이 담긴다.
        ///
        /// <paramref name="reason"/> 은 **로그용**이다. 클라이언트에게는 거부 사유를 세분해서
        /// 돌려주지 않는다 — 어느 검사에서 걸렸는지 알려주면 위조를 도와주는 꼴이다.
        /// </summary>
        public static bool Verify(string rawToken, out WorldEntryToken token, out string reason)
        {
            token = default;

            if (string.IsNullOrEmpty(rawToken)) { reason = "토큰이 비어 있다"; return false; }

            if (!WorldEntryTokenSecret.TryGet(out var key, out var keyFailure))
            {
                // 배포 환경은 EnforceOrQuit 에서 이미 죽는다. 여기 오는 건 에디터뿐이다.
                if (Application.isEditor)
                {
                    Debug.LogError(
                        $"[WorldEntryToken] **입장 검증이 꺼져 있다** — {keyFailure}. " +
                        "에디터라 통과시키지만, 이 상태로 배포되면 무인증 서버가 된다.");
                    token = EditorBypass(rawToken);
                    reason = null;
                    return true;
                }
                reason = $"검증 키 없음 ({keyFailure})";
                return false;
            }

            return VerifyWithKey(rawToken, key, out token, out reason);
        }

        /// <summary>
        /// 키를 명시해 검증한다. 키 조달 경로(환경변수·Secret 파일)와 검증 논리를 분리해 두면
        /// 보안상 중요한 이 부분을 테스트에서 직접 겨눌 수 있다.
        /// </summary>
        public static bool VerifyWithKey(string rawToken, byte[] key,
                                         out WorldEntryToken token, out string reason)
        {
            token = default;
            if (string.IsNullOrEmpty(rawToken)) { reason = "토큰이 비어 있다"; return false; }
            if (key == null || key.Length == 0) { reason = "검증 키 없음"; return false; }

            var parts = rawToken.Split('.');
            if (parts.Length != 3) { reason = "JWT 형식이 아니다"; return false; }

            // --- 서명 ---
            // 서명 대상은 base64url 로 인코딩된 원문 그대로다. 디코딩 후 재직렬화하면
            // 공백·키 순서가 달라져 서명이 깨진다.
            byte[] expected;
            using (var hmac = new HMACSHA256(key))
                expected = hmac.ComputeHash(Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]));

            if (!TryBase64Url(parts[2], out var actual)) { reason = "서명 디코딩 실패"; return false; }
            if (!FixedTimeEquals(expected, actual)) { reason = "서명 불일치"; return false; }

            // --- 헤더 ---
            if (!TryBase64Url(parts[0], out var headerBytes)) { reason = "헤더 디코딩 실패"; return false; }
            var header = JsonUtility.FromJson<Header>(Encoding.UTF8.GetString(headerBytes));
            // alg 를 확인하지 않으면 alg=none 토큰이 통과한다.
            if (header == null || header.alg != "HS256") { reason = $"alg 가 HS256 이 아니다"; return false; }

            // --- 클레임 ---
            if (!TryBase64Url(parts[1], out var payloadBytes)) { reason = "payload 디코딩 실패"; return false; }
            var c = JsonUtility.FromJson<Claims>(NormalizeAudience(Encoding.UTF8.GetString(payloadBytes)));
            if (c == null) { reason = "payload 파싱 실패"; return false; }

            if (c.iss != ExpectedIssuer) { reason = $"iss 불일치"; return false; }
            if (!HasAudience(c)) { reason = "aud 불일치"; return false; }
            if (c.worldId != ExpectedWorldId) { reason = "worldId 불일치"; return false; }
            if (c.channelId != ExpectedChannelId) { reason = "channelId 불일치"; return false; }
            if (string.IsNullOrEmpty(c.jti)) { reason = "jti 없음 — 재사용을 막을 수 없다"; return false; }

            var now = WorldEntryToken.UnixNow();
            if (c.exp <= 0) { reason = "exp 없음"; return false; }
            if (now > c.exp + ClockSkewSeconds) { reason = "만료됨"; return false; }
            // 아직 시작되지 않은 토큰. 시계가 크게 어긋난 발급자를 걸러낸다.
            if (c.iat > 0 && c.iat > now + ClockSkewSeconds) { reason = "iat 가 미래다"; return false; }

            token = new WorldEntryToken(
                c.jti, c.sub, c.role, c.playerId, c.nickname, c.sessionId,
                c.worldId, c.channelId,
                // 게스트·미저장 회원은 avatarCode 클레임 자체가 없다. 빈 문자열을 외형 코드로
                // 넘기면 디코더가 실패하므로 null 로 정규화해 기본값 폴백에 맡긴다.
                string.IsNullOrEmpty(c.avatarCode) ? null : c.avatarCode,
                c.exp);

            reason = null;
            return true;
        }

        /// <summary>
        /// 키가 없는 에디터에서만 쓰는 통과용 신원. **배포 경로에는 도달하지 않는다.**
        /// 검증을 건너뛴다는 사실이 신원에 그대로 드러나도록 이름을 붙인다.
        /// </summary>
        static WorldEntryToken EditorBypass(string rawToken) => new WorldEntryToken(
            jti: "editor-" + rawToken.GetHashCode().ToString("x8"),
            subject: "editor", role: "MEMBER", playerId: "0",
            nickname: "Editor", sessionId: "editor",
            worldId: ExpectedWorldId, channelId: ExpectedChannelId,
            avatarCode: null,
            expiresAtUnix: WorldEntryToken.UnixNow() + 3600);

        static bool HasAudience(Claims c)
        {
            if (c.aud != null)
                foreach (var a in c.aud)
                    if (a == ExpectedAudience) return true;
            return false;
        }

        /// <summary>
        /// RFC 7519 §4.1.3 — <c>aud</c> 는 배열일 수도, 단일 문자열일 수도 있다.
        /// 실제 백엔드(Spring)는 <b>단일 문자열</b>로 보내고 Nimbus·목 서버·에디터 서명기는
        /// 배열로 보낸다. <c>Claims.aud</c> 는 <c>string[]</c> 라 JsonUtility 가 문자열형을
        /// <b>조용히 null 로 두고</b>, 그대로면 "aud 불일치" 로 접속이 거부된다 —
        /// 실제 백엔드에는 영원히 못 붙는 상태였다 (S15P21A604-340 실측).
        /// 파싱 전에 문자열형만 배열형으로 고쳐 쓴다. 값 안의 이스케이프(\")를 건너뛰며
        /// 문자열 끝을 찾으므로 값에 따옴표가 들어 있어도 안전하다.
        /// </summary>
        public static string NormalizeAudience(string payloadJson)
        {
            int key = payloadJson.IndexOf("\"aud\"", StringComparison.Ordinal);
            if (key < 0) return payloadJson;

            int i = key + 5;
            while (i < payloadJson.Length && (payloadJson[i] == ' ' || payloadJson[i] == ':')) i++;
            if (i >= payloadJson.Length || payloadJson[i] != '"') return payloadJson; // 이미 배열이거나 형식 밖 — 손대지 않는다

            int start = i;
            i++;
            while (i < payloadJson.Length)
            {
                if (payloadJson[i] == '\\') { i += 2; continue; }
                if (payloadJson[i] == '"') break;
                i++;
            }
            if (i >= payloadJson.Length) return payloadJson; // 닫는 따옴표가 없다 — 어차피 파싱이 실패한다

            return payloadJson.Substring(0, start) + "[" +
                   payloadJson.Substring(start, i - start + 1) + "]" +
                   payloadJson.Substring(i + 1);
        }

        /// <summary>JWT 는 padding 없는 base64url 을 쓴다.</summary>
        static bool TryBase64Url(string value, out byte[] bytes)
        {
            bytes = null;
            if (string.IsNullOrEmpty(value)) return false;

            var s = value.Replace('-', '+').Replace('_', '/');
            switch (s.Length % 4)
            {
                case 2: s += "=="; break;
                case 3: s += "="; break;
                case 1: return false;   // base64 로 나올 수 없는 길이
            }

            try { bytes = Convert.FromBase64String(s); return true; }
            catch (FormatException) { return false; }
        }

        /// <summary>
        /// 길이·내용을 조기 반환 없이 비교한다. 바이트 단위로 빠져나가면 비교 시간이
        /// 일치 길이에 비례해 서명을 한 바이트씩 맞춰볼 수 있다.
        /// </summary>
        static bool FixedTimeEquals(byte[] a, byte[] b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }

        [Serializable]
        class Header
        {
            public string alg;
        }

        [Serializable]
        class Claims
        {
            public string iss;
            public string[] aud;
            public string sub;
            public string jti;
            public long exp;
            public long iat;
            public string role;
            public string playerId;
            public string nickname;
            public string sessionId;
            public string worldId;
            public string channelId;
            public string avatarCode;
        }
    }
}
