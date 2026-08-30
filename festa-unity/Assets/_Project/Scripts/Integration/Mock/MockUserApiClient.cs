using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>지연 시뮬레이션은 Awaitable 사용 — Task.Delay는 WebGL에서 완료되지 않는다.</summary>
    public class MockUserApiClient : IUserApiClient
    {
        public async Task<UserProfileDto> GetMyProfileAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.08f);
            return new UserProfileDto
            {
                userId = 12,
                nickname = "MockUser",
                avatarCode = "sk_01" // AvatarCatalog에 등록된 코드
            };
        }

        public async Task<bool> UpdateMyAvatarAsync(string encodedAppearance)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            // 실제 구현: PUT /api/v1/users/me/avatar { "avatarCode": "..." } (#24 확정)
            // 메서드는 spec 계약서 기준(PUT), 필드명은 #24 에서 avatarCode 로 확정됐다 —
            // DB avatar_code·JPA avatarCode·Unity AvatarCode 와 같은 이름이다.
            Debug.Log($"[MockUserApi] 아바타 저장(모의): {encodedAppearance}");
            return true;
        }

        /// <summary>
        /// 개발용 world session. **토큰은 진짜로 서명한다** (S15P21A604-331).
        ///
        /// <para>전에는 <c>"mock-connection-token"</c> 이라는 고정 문자열을 줬다. S15P21A604-85 로
        /// 서버가 HS256 서명을 검증하게 된 뒤로 그 값은 <b>"JWT 형식이 아니다"</b> 로 항상 거부된다 —
        /// <c>ApiConfig.useMockApi = 1</c> 이라 <b>로컬 개발 접속 전체가 막혀 있었다.</b>
        /// -85 때 <see cref="Festa.Diagnostics.LoadTestBot"/> 은 같이 고쳤는데 여기가 빠졌다.</para>
        ///
        /// <para>서버에 "Mock 은 봐준다" 예외를 두지 않는다 — <b>그 예외가 곧 우회로가 된다.</b>
        /// 봇과 같이, 서버와 같은 키로 진짜 grant 를 서명해 <b>실제 승인 경로를 그대로 탄다.</b></para>
        /// </summary>
        public async Task<WorldSessionDto> CreateWorldSessionAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.12f);

            // jti 는 매번 달라야 한다 — 같은 값을 다시 쓰면 재사용 원장(GrantReplayLedger)이 거부한다.
            var jti = $"mock-{System.Guid.NewGuid():N}";
            if (!Festa.Network.WorldEntryTokenSigner.TryIssue(
                    jti, "12", "MockUser", "sk_01", out var token, out var failure))
            {
                // **조용히 고정 문자열로 되돌아가지 않는다.** 그러면 서버 로그에는
                // "JWT 형식이 아니다" 만 남고 진짜 원인(키 없음)이 가려진다 (T-24).
                Debug.LogError(
                    $"[MockUserApi] grant 를 서명하지 못해 world session 을 발급하지 않는다 — {failure}. " +
                    "서버와 같은 CONNECTION_TOKEN_SECRET(_FILE) 을 이 프로세스에도 주입해라. " +
                    "(WebGL 은 환경변수가 없다 — 브라우저에서 Mock 접속을 검증하려면 실서버 경로를 써야 한다)");
                return null;
            }

            // MVP: 항상 단일 채널. 자동 Channeling은 P2지만 계약은 지금부터 사용한다.
            return new WorldSessionDto
            {
                sessionId = "ws_mock_001",
                worldId = "11F",
                channelId = "11F-01",
                endpoint = new WorldEndpointDto { scheme = "ws", host = "127.0.0.1", port = 7777 },
                connectionToken = token,
                expiresAt = "2999-12-31T00:00:00+09:00"
            };
        }
    }
}
