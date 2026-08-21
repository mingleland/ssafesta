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
