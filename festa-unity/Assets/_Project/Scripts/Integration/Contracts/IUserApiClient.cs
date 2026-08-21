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
    }
}
