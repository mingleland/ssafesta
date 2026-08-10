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
                avatarCode = "default"
            };
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
                serverEndpoint = "127.0.0.1:7777",
                connectionToken = "mock-connection-token",
                expiresAt = "2999-12-31T00:00:00+09:00"
            };
        }
    }
}
