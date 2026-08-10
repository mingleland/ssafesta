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
        [Tooltip("체크 시 Spring/FastAPI 대신 Mock 클라이언트를 사용한다.")]
        [SerializeField] bool _useMockApi = true;

        [Tooltip("Spring Boot API base URL (Mock 해제 시 사용)")]
        [SerializeField] string _springBaseUrl = "http://localhost:8080";

        [Tooltip("FastAPI AI base URL (Mock 해제 시 사용)")]
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

            ApiServices.Init(_useMockApi, _springBaseUrl, _aiBaseUrl);
            Debug.Log($"[GameBootstrap] Initialized. useMockApi={_useMockApi}");
        }
    }
}
