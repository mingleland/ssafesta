using System.Threading.Tasks;

namespace Festa.Integration
{
    /// <summary>Spring User/Session API 경계 (POC C).</summary>
    public interface IUserApiClient
    {
        Task<UserProfileDto> GetMyProfileAsync();

        /// <summary>
        /// World 접속 세션 요청 (doc 16 §3).
        /// Unity는 이 응답의 endpoint/token으로만 Dedicated Server에 연결한다.
        /// </summary>
        Task<WorldSessionDto> CreateWorldSessionAsync();

        /// <summary>
        /// 프로필 아바타 외형 저장 (인코딩된 AvatarAppearance 문자열).
        /// Spring 미구현 상태에서는 Mock이 성공만 반환한다 — 저장 실패해도 월드 내 외형은 유지된다.
        /// </summary>
        Task<bool> UpdateMyAvatarAsync(string encodedAppearance);

        /// <summary>
        /// 아바타 파츠 카탈로그 + 이 사용자의 보유 여부 (GitLab #120 §2).
        ///
        /// <para>실패하면 <c>null</c> 이다. <b>호출자는 null 을 "전부 해제" 로 해석해서는
        /// 안 된다</b> — 조용히 열어 버리면 잠금이 동작하는지 아무도 모르는 채로 나간다(T-24).
        /// 잠긴 상태를 유지하고 오류를 드러내야 한다.</para>
        ///
        /// <para>게스트도 조회는 성공한다 — 무료 품목만 <c>owned</c> 로 온다. 구매만
        /// <c>MEMBER_ONLY</c> 로 거부된다.</para>
        /// </summary>
        Task<CatalogItemsDto> GetAvatarPartCatalogAsync();

        /// <summary>
        /// 파츠 구매 — <c>POST /api/v1/catalog/items/{itemId}/purchases</c> (GitLab #120 §2).
        ///
        /// <para><b>이중 차감은 서버가 막는다</b> — 지갑 <c>idempotencyKey</c> 와
        /// <c>UNIQUE(user_id, catalog_item_id)</c> 두 겹이다(#120 §2). 클라이언트의 연타 방지는
        /// 그 위에 얹는 편의이지 안전장치가 아니다. 두 번 눌려 두 번 가더라도 두 번째는
        /// <c>ITEM_ALREADY_OWNED</c> 로 떨어지고 코인은 한 번만 빠진다.</para>
        ///
        /// <para>게스트는 <c>MEMBER_ONLY</c> 로 거부된다 — 호출 전에 화면에서 걸러야 한다.</para>
        /// </summary>
        Task<PurchaseResult> PurchaseAvatarPartAsync(long itemId);

        /// <summary>
        /// 저장해 둔 아바타 프리셋 목록 — <c>GET /api/v1/users/me/avatar/presets</c> (GitLab#237).
        ///
        /// <para><b>빈 칸은 오지 않는다.</b> 세 칸 중 저장된 것만 담겨 오므로 길이가 0~3 이다.
        /// 실패하면 <c>null</c> 이다 — 호출자는 null 을 "세 칸 다 비었다" 로 읽어서는 안 된다.
        /// 조용히 비어 있는 것으로 그리면 사용자가 저장한 외형이 사라진 것처럼 보이고,
        /// 거기에 덮어쓰면 실제로 사라진다(T-24 와 같은 부류).</para>
        /// </summary>
        Task<AvatarPresetDto[]> GetAvatarPresetsAsync();

        /// <summary>
        /// 프리셋 한 칸 저장(덮어쓰기 포함) — <c>PUT /api/v1/users/me/avatar/presets/{slot}</c>.
        ///
        /// <para><paramref name="slot"/> 은 1·2·3 만 유효하다. 게스트는 서버가
        /// <c>MEMBER_ONLY</c> 로 거부한다(헌법 12조 — 게스트는 비영속).</para>
        /// </summary>
        Task<bool> SaveAvatarPresetAsync(int slot, string encodedAppearance);

        /// <summary>프리셋 한 칸 비우기 — <c>DELETE /api/v1/users/me/avatar/presets/{slot}</c>.</summary>
        Task<bool> DeleteAvatarPresetAsync(int slot);
    }
}
