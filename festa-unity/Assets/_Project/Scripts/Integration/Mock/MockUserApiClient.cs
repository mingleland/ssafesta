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
            // 실제 구현: PUT /api/v1/users/me/avatar { "avatar": "..." }
            // 메서드·필드명은 specs/013-avatar-customization/contracts/avatar-profile-api.md 기준이다.
            // 아래 UserProfileDto.avatarCode 는 GET /users/me 응답 필드로, BE 가 프로필 응답에
            // 외형을 함께 담을지 확정되면 그때 이름을 맞춘다 (Issue #24).
            Debug.Log($"[MockUserApi] 아바타 저장(모의): {encodedAppearance}");
            return true;
        }

        public async Task<WorldSessionDto> CreateWorldSessionAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.12f);
            // MVP: 항상 단일 채널. 자동 Channeling은 P2지만 계약은 지금부터 사용한다.
            return new WorldSessionDto
            {
                sessionId = "ws_mock_001",
                worldId = "11F",
                channelId = "11F-01",
                endpoint = new WorldEndpointDto { scheme = "ws", host = "127.0.0.1", port = 7777 },
                connectionToken = "mock-connection-token",
                expiresAt = "2999-12-31T00:00:00+09:00"
            };
        }
    }
}
