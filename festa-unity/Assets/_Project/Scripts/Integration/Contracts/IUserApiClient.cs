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
    }
}
