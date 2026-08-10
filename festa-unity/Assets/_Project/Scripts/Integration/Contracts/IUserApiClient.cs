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
    }
}
