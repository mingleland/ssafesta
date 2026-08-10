using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// API 클라이언트 조립 지점 (composition root).
    /// GameBootstrap이 Init을 호출하며, 소비자는 인터페이스만 참조한다.
    /// Mock ↔ 실서버 전환은 여기 한 곳에서만 일어난다 (POC C/D 요구사항).
    /// </summary>
    public static class ApiServices
    {
        public static IBoothApiClient Booth { get; private set; }
        public static IUserApiClient User { get; private set; }
        public static IAiAgentClient Ai { get; private set; }
        public static IAccessTokenProvider TokenProvider { get; private set; }

        public static bool IsMock { get; private set; }

        /// <summary>ApiConfig(SO) 기반 초기화 — 권장 경로.</summary>
        public static void Init(ApiConfig config)
        {
            var entry = config != null ? config.Active : null;
            Init(config == null || config.useMockApi,
                 entry?.springBaseUrl ?? "",
                 entry?.aiBaseUrl ?? "");
        }

        public static void Init(bool useMock, string springBaseUrl, string aiBaseUrl)
        {
            IsMock = useMock;
            TokenProvider = new EmptyAccessTokenProvider(); // Auth spec 확정 시 실제 구현으로 교체

            if (useMock)
            {
                Booth = new MockBoothApiClient();
                User = new MockUserApiClient();
                Ai = new MockAiAgentClient();
            }
            else
            {
                Booth = new HttpBoothApiClient(springBaseUrl, TokenProvider);
                // TODO: HttpUserApiClient / SseAiAgentClient — 해당 기능 spec 작성 후 구현
                User = new MockUserApiClient();
                Ai = new MockAiAgentClient();
                Debug.LogWarning("[ApiServices] User/Ai HTTP 구현 전 — Mock으로 대체 중");
            }

            Debug.Log($"[ApiServices] Init — mock={useMock} spring={springBaseUrl}");
        }

        /// <summary>Bootstrap 없이 씬을 단독 실행할 때의 안전장치.</summary>
        public static void EnsureInitialized()
        {
            if (Booth == null) Init(true, "", "");
        }
    }
}
