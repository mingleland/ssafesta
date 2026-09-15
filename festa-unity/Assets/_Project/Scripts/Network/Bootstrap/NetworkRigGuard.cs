using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// `@Network` 리그(NetworkManager + ConnectionManager + NetworkBootstrap + DevConnectionHud)가
    /// **씬 재로드마다 중복 생성되는 것**을 막는다 (S15P21A604-313).
    ///
    /// ── 왜 필요한가 ────────────────────────────────────────
    /// `NetworkManager` 는 DontDestroyOnLoad 로 살아남는데 `@Network` 는 `main.unity` 안에 있다.
    /// 그래서 `main` 을 다시 로드할 때마다 씬이 새 `@Network` 를 또 만들고, **NGO 는 그 중복을
    /// 지워 주지 않는다** (실측: 재로드 후 NetworkManager 2개가 모두 살아 있고, 먼저 만들어진
    /// 쪽이 싱글턴을 유지한다).
    ///
    /// 그 결과 중복 인스턴스의 `ConnectionManager.Start()` 가 살아 있는 싱글턴에
    /// `ConnectionApprovalCallback +=` 를 또 시도해 예외로 죽는다:
    ///   `InvalidOperationException: Only one ConnectionApprovalCallback can be registered at a time.`
    /// 게다가 중복 `DevConnectionHud.Start()` 가 자동 접속 요청을 먼저 소비해버려서,
    /// 곧 버려질 인스턴스가 접속을 가져간다. 접속이 실패하면 플레이어가 스폰되지 않고
    /// 씬에 저장된 Main Camera 가 그대로 남아 엉뚱한 화면이 보인다.
    ///
    /// ── 왜 이 방식인가 ────────────────────────────────────
    /// `ConnectionManager.cs` 는 헌법 27조 동결 대상이라 손대지 않는다. 씬에서 `@Network` 를
    /// 빼내 부트스트랩에서 1회 생성하는 쪽이 더 근본적이지만 씬 구조 변경이 따른다.
    /// **중복을 그 자리에서 없애는 별도 컴포넌트**가 동결 파일·씬 구조를 모두 건드리지 않는
    /// 가장 좁은 수정이다.
    /// </summary>
    [DefaultExecutionOrder(-10000)]
    public class NetworkRigGuard : MonoBehaviour
    {
        static NetworkRigGuard s_owner;

        /// <summary>살아남은 리그. 진단용으로만 읽는다.</summary>
        public static GameObject ActiveRig => s_owner != null ? s_owner.gameObject : null;

        void Awake()
        {
            if (s_owner != null && s_owner != this)
            {
                // **SetActive(false) 를 먼저 한다.** Destroy 는 프레임 끝에 처리되므로
                // 그것만 걸어 두면 그 사이 이 오브젝트의 Start() 들이 전부 돌아버린다 —
                // 막으려던 ConnectionApprovalCallback 등록이 바로 그 Start() 에서 일어난다.
                // 비활성화하면 Start() 가 호출되지 않는다.
                gameObject.SetActive(false);
                Destroy(gameObject);

                // 조용히 지우지 않는다. 중복이 생겼다는 사실 자체가 씬 구성 신호다 (T-24).
                Debug.Log("[NetworkRigGuard] 중복 @Network 를 정리했다 — 먼저 만들어진 리그를 유지한다. " +
                          "씬 재로드 시 정상 동작이다 (S15P21A604-313).");
                return;
            }

            s_owner = this;
            // NetworkManager 가 어차피 DontDestroyOnLoad 로 올린다. 명시해 두면
            // 이 컴포넌트만 따로 붙여도 같은 수명을 갖는다.
            DontDestroyOnLoad(gameObject);
        }

        void OnDestroy()
        {
            if (s_owner == this) s_owner = null;
        }
    }
}
