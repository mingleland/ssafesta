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
        public static IGameResultClient Game { get; private set; }
        /// <summary>코인 잔액 (spec 003). 실서버는 GET /wallets/me, Mock 은 100 고정.</summary>
        public static IWalletClient Wallet { get; private set; }
        /// <summary>slot machine 판정 (S15P21A604-439). BE 계약 전이라 두 모드 모두 Mock — 결과 DTO 의 simulated 가 그 사실을 실어 나른다.</summary>
        public static ISlotMachineClient Slot { get; private set; }
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
            // 실서버 경로는 WebGL 호스트가 SendMessage 로 밀어 넣은 Access Token 을 쓴다 (AuthBridge).
            // Mock 경로는 헤더를 붙이지 않는다 — Mock 서버는 인증을 요구하지 않는다.
            TokenProvider = useMock ? new EmptyAccessTokenProvider() : new HostAccessTokenProvider();

            if (useMock)
            {
                Booth = new MockBoothApiClient();
                User = new MockUserApiClient();
                Ai = new MockAiAgentClient();
                Game = new MockGameResultClient();
                Wallet = new MockWalletClient();
                Slot = new MockSlotMachineClient();
            }
            else
            {
                Booth = new HttpBoothApiClient(springBaseUrl, TokenProvider);
                User = new HttpUserApiClient(springBaseUrl, TokenProvider);
                // TODO: SseAiAgentClient — spec 008 SSE 계약 확정 후 구현
                Ai = new MockAiAgentClient();
                Debug.LogWarning("[ApiServices] Ai HTTP 구현 전 — Mock으로 대체 중");
                // spec 003(wallet-coin) 지급 경로가 붙어야 실서버 구현이 의미를 갖는다.
                // 그전까지 Mock 을 쓰되, 그 사실을 경고로 드러낸다 — 조용한 대체 금지 (T-24).
                Game = new MockGameResultClient();
                Debug.LogWarning("[ApiServices] Game HTTP 구현 전 — Mock으로 대체 중 (spec 014, FR-008 미성립)");
                Wallet = new HttpWalletClient(springBaseUrl, TokenProvider);
                // slot machine 판정 엔드포인트는 BE 미구현(docs/26 ③). 판정은 Mock, 잔액 표시만 실서버에서 시드한다.
                Slot = new MockSlotMachineClient();
                Debug.LogWarning("[ApiServices] Slot HTTP 구현 전 — Mock 판정, 코인 원장 미반영 (S15P21A604-439)");
            }

            Debug.Log($"[ApiServices] Init — mock={useMock} spring={springBaseUrl}");
        }

        /// <summary>
        /// Bootstrap 없이 씬을 단독 실행할 때의 안전장치.
        ///
        /// <para><b>여기로 떨어지면 소리를 낸다.</b> 이 폴백은 에디터에서 씬 하나만 띄워 볼 때를
        /// 위한 것인데, 실제로는 CharacterLobby(빌드 첫 씬)에 GameBootstrap 이 없어서 WebGL 도
        /// 이 경로로 시작했다 — 로비가 Mock 프로필·Mock 카탈로그로 돌고, main 에 들어가서야
        /// 실서버로 바뀌었다 (S15P21A604-418). 조용히 Mock 이 되면 아무도 모른다(T-24 와 같은
        /// 실패 양상). 경고가 아니라 에러로 남겨 콘솔에서 바로 보이게 한다.</para>
        /// </summary>
        public static void EnsureInitialized()
        {
            if (Booth != null) return;
            Debug.LogError("[ApiServices] GameBootstrap 없이 시작됐다 — Mock API 로 동작한다. " +
                           "첫 씬에 @GameBootstrap(ApiConfig) 이 있어야 실서버에 붙는다 (S15P21A604-418).");
            Init(true, "", "");
        }
    }
}
