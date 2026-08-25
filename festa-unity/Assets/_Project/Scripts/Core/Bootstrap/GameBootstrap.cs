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

            if (_apiConfig != null)
                ApiServices.Init(_apiConfig);
            else
                ApiServices.Init(_useMockApi, _springBaseUrl, _aiBaseUrl);

            Debug.Log($"[GameBootstrap] Initialized. mock={ApiServices.IsMock}");
        }
    }
}
