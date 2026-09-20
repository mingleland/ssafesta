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
        /// Mock 에는 재고 서버가 없다 — <c>null</c> 을 돌려준다 (GitLab #120 §2).
        ///
        /// <para><b>"전부 보유" 를 꾸며내지 않는다.</b> 그러면 Mock 에서만 팔레트가 전부 열려,
        /// 잠금이 깨진 것을 실서버에 붙이기 전까지 아무도 모른다. 대신 로비가
        /// <see cref="ApiServices.IsMock"/> 를 보고 <b>명시적으로</b> 개발 모드 해제를 선택한다 —
        /// 조용한 폴백과 달리 로그와 화면에 드러난다.</para>
        /// </summary>
        public async Task<CatalogItemsDto> GetAvatarPartCatalogAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            Debug.Log("[MockUserApi] 카탈로그 보유 정보 없음 — 로비가 개발 모드로 판단한다 (실서버에서는 owned 를 쓴다)");
            return null;
        }

        /// <summary>
        /// Mock 구매. **성공만 돌려주지 않는다** — Mock 에서는 파츠가 전부 해제라 구매 화면 자체가 뜨지 않고,
        /// 그런데도 이 경로가 불렸다면 화면 분기에 구멍이 있다는 뜻이다. 드러내고 실패로 남긴다 (T-24).
        /// </summary>
        public async Task<PurchaseResult> PurchaseAvatarPartAsync(long itemId)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            Debug.LogWarning($"[MockUserApi] 구매 요청이 왔다 — Mock 은 파츠가 전부 해제라 구매가 뜰 이유가 없다 (itemId={itemId}). 화면 분기를 확인하라.");
            return PurchaseResult.Fail("MOCK_NO_PURCHASE", "Mock 모드에서는 구매하지 않는다 — 파츠는 이미 전부 해제 상태다");
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

        // ── 아바타 프리셋 (GitLab#237) ────────────────────────────────────────
        //
        // BE 엔드포인트가 아직 없어 UI 를 먼저 만들고 여기서 확인한다. **프로세스 메모리 한정**이고
        // 에디터를 닫으면 사라진다 — 영속하는 척하지 않는다. 실서버가 붙으면 이 구현은 그대로 두고
        // HttpUserApiClient 쪽만 쓰인다.
        static readonly System.Collections.Generic.Dictionary<int, AvatarPresetDto> s_presets = new();

        public async Task<AvatarPresetDto[]> GetAvatarPresetsAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            var list = new System.Collections.Generic.List<AvatarPresetDto>();
            for (int slot = 1; slot <= 3; slot++)
                if (s_presets.TryGetValue(slot, out var p)) list.Add(p);
            Debug.Log($"[MockUserApi] 프리셋 조회(모의) — {list.Count}칸 사용 중");
            return list.ToArray();
        }

        public async Task<bool> SaveAvatarPresetAsync(int slot, string encodedAppearance)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            if (slot < 1 || slot > 3 || string.IsNullOrEmpty(encodedAppearance))
            {
                Debug.LogWarning($"[MockUserApi] 프리셋 저장 거부 — slot={slot} 길이={encodedAppearance?.Length ?? -1}");
                return false;
            }
            s_presets[slot] = new AvatarPresetDto
            {
                slot = slot,
                avatarCode = encodedAppearance,
                updatedAt = System.DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ"),
            };
            Debug.Log($"[MockUserApi] 프리셋 {slot}번 저장(모의): 길이 {encodedAppearance.Length}");
            return true;
        }

        public async Task<bool> DeleteAvatarPresetAsync(int slot)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            bool removed = s_presets.Remove(slot);
            Debug.Log($"[MockUserApi] 프리셋 {slot}번 비우기(모의): {(removed ? "있었음" : "이미 비어 있었음")}");
            return true;
        }
    }
}
