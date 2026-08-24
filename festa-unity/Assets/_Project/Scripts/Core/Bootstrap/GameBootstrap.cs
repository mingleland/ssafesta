using Festa.Integration;
using UnityEngine;

namespace Festa.Core
{
    /// <summary>
    /// 게임 전역 진입점. Bootstrap 씬의 루트 오브젝트에 부착한다.
    /// 서비스 초기화(API 클라이언트 조립)만 담당하고, 게임 로직을 갖지 않는다.
    /// </summary>
    public class GameBootstrap : MonoBehaviour
    {
        [Header("Integration")]
        [Tooltip("환경별 API 설정 (FESTA/Api Config로 생성). 미할당 시 아래 레거시 필드 사용")]
        [SerializeField] ApiConfig _apiConfig;

        [Header("Legacy (ApiConfig 미할당 시)")]
        [SerializeField] bool _useMockApi = true;
        [SerializeField] string _springBaseUrl = "http://localhost:8080";
        [SerializeField] string _aiBaseUrl = "http://localhost:8000";

        public static GameBootstrap Instance { get; private set; }

        void Awake()
        {
            if (Instance != null)
            {
                Destroy(gameObject);
                return;
            }

            Instance = this;
            DontDestroyOnLoad(gameObject);

            // 프레임 상한. 무제한(-1)이면 한 PC 에서 두 인스턴스(호스트+클라이언트)로
            // 테스트할 때 서로 CPU/GPU 를 뺏으며 둘 다 버벅인다 — POC 데모도 그 시나리오다.
            // WebGL 은 브라우저 vsync 가 프레임을 조율하므로 건드리지 않는다.
#if !UNITY_WEBGL
            Application.targetFrameRate = 60;
#endif

            if (_apiConfig != null)
                ApiServices.Init(_apiConfig);
            else
                ApiServices.Init(_useMockApi, _springBaseUrl, _aiBaseUrl);

            Debug.Log($"[GameBootstrap] Initialized. mock={ApiServices.IsMock}");
        }
    }
}
