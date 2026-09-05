using Festa.Integration;
using Festa.World;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.Network
{
    /// <summary>
    /// POC 전용 개발 접속 HUD (IMGUI). 정식 로그인/월드 진입 UI가 생기면 제거한다.
    /// Dedicated Server(batch mode)에서는 표시되지 않는다.
    /// </summary>
    [RequireComponent(typeof(ConnectionManager))]
    public class DevConnectionHud : MonoBehaviour
    {
        string _address = "127.0.0.1";
        string _port = "7777";
        string _nickname = "Player";

        // POC: 접속마다 다른 아바타가 배정되도록 순환 (멀티 접속 시 외형 구분 확인용).
        // 정식 구현에서는 Spring User 프로필의 avatarCode를 사용한다.
        static readonly string[] AvatarCodes = { "sk_01", "sk_02", "sk_03" };
        static int s_avatarIndex;
        static string NextAvatarCode() => AvatarCodes[s_avatarIndex++ % AvatarCodes.Length];

        ConnectionManager _connection;

        void Awake() => _connection = GetComponent<ConnectionManager>();

        void Start()
        {
            // Only the lobby entry path starts a session automatically. Running
            // the world scene directly still leaves the development controls usable.
            TryConsumeEntryRequest();
        }

        // ── 재진입 (S15P21A604-332) ────────────────────────────────
        //
        // **`Start()` 한 번으로는 두 번째 월드 진입을 못 잡는다.**
        //
        // 이 오브젝트(`@Network`)는 `NetworkManager`·`ConnectionManager`·이 HUD 를 한 몸으로
        // 갖고 있고, 첫 `main` 로드에서 `DontDestroyOnLoad` 로 옮겨진다. 로비로 돌아갔다가
        // 다시 월드로 들어오면 새 `main` 씬에도 `@Network` 가 있지만 **NGO 의 NetworkManager
        // 싱글턴 중복 제거가 그 오브젝트를 통째로 파괴한다** — 새 HUD 도 같이 사라진다.
        // 살아남는 것은 **옛** 인스턴스이고, 그 `Start()` 는 이미 돌았으므로 다시 돌지 않는다.
        // 그 결과 접속 요청이 영영 소비되지 않아 **두 번째 진입은 조용히 접속되지 않았다**
        // (요청 플래그가 `1` 로 남아 있는 것이 그 증거였다).
        //
        // 그래서 진입 판단을 **씬 로드마다** 한다. `WorldEntryGate` 가 같은 함정을
        // 같은 방식으로 이미 고쳤다 (S15P21A604-312) — 한 번만 도는 진입 훅이 이 코드베이스에서
        // 두 번째로 낸 같은 사고다.
        void OnEnable() => SceneManager.sceneLoaded += OnSceneLoaded;
        void OnDisable() => SceneManager.sceneLoaded -= OnSceneLoaded;

        void OnSceneLoaded(Scene scene, LoadSceneMode mode)
        {
            if (mode != LoadSceneMode.Single) return;
            if (scene.name != AvatarSceneHandoff.WorldSceneName) return;
            TryConsumeEntryRequest();
        }

        void TryConsumeEntryRequest()
        {
            if (!AvatarSceneHandoff.ConsumeWorldConnectionRequest()) return;

            // 이미 붙어 있으면 요청만 비우고 끝낸다 — 겹쳐 걸면 전송 계층이 원인을
            // 알려주지 않는 실패만 남긴다 (T-182).
            var nm = Unity.Netcode.NetworkManager.Singleton;
            if (nm != null && (nm.IsListening || nm.IsClient))
            {
                Debug.Log("[DevConnectionHud] 이미 접속 상태라 진입 요청만 소비한다.");
                return;
            }

            ConnectViaSessionApi();
        }

        // 데디케이티드 서버 빌드는 IMGUI 모듈이 스트립된다. 그러면 유니티가 기동 시
        // "OnGUI function detected on MonoBehaviour, but not called" 경고를 띄우는데,
        // 이건 **메서드가 존재한다는 사실만으로** 뜨므로 아래의 isBatchMode 가드로는 못 막는다.
        // 메서드 자체를 서버 빌드에서 컴파일 제외해야 한다 (S15P21A604-314).
        //
        // UNITY_EDITOR 를 함께 두는 이유: UNITY_SERVER 는 빌드 타깃이 Dedicated Server 이면
        // 에디터에도 정의된다 (T-182). 그 조건만 쓰면 타깃을 서버로 둔 순간 에디터에서
        // 이 HUD 가 사라진다 — 개발 중에 접속 수단을 잃는다.
#if UNITY_EDITOR || !UNITY_SERVER
        void OnGUI()
        {
            if (Application.isBatchMode) return;
            // 패널은 개발 도구다 — 릴리즈(비 Development) 빌드에서는 그리지 않는다.
            // 이 컴포넌트의 진입 소비(TryConsumeEntryRequest) 로직은 빌드와 무관하게 돌아야
            // 하므로 컴포넌트가 아니라 **그리기만** 게이트한다 (S15P21A604-348).
            if (!UnityEngine.Debug.isDebugBuild && !Application.isEditor) return;

            var nm = Unity.Netcode.NetworkManager.Singleton;
            if (nm == null) return;

            GUILayout.BeginArea(new Rect(10, 10, 260, 260), GUI.skin.box);
            GUILayout.Label("FESTA Dev Connection (POC)");

            if (!nm.IsClient && !nm.IsServer)
            {
                GUILayout.BeginHorizontal();
                GUILayout.Label("Addr", GUILayout.Width(40));
                _address = GUILayout.TextField(_address);
                GUILayout.Label("Port", GUILayout.Width(35));
                _port = GUILayout.TextField(_port, GUILayout.Width(50));
                GUILayout.EndHorizontal();

                GUILayout.BeginHorizontal();
                GUILayout.Label("Name", GUILayout.Width(40));
                _nickname = GUILayout.TextField(_nickname);
                GUILayout.EndHorizontal();

                // Host = 서버 + 클라이언트를 한 인스턴스에서. 스폰·이동·상호작용을
                // 에디터 하나로 검증할 때 가장 빠르고, 승인 흐름도 같은 경로를 탄다.
                // (원격 표현 검증은 인스턴스가 둘 필요하다 — 그때 Server + Client 를 쓴다.)
                if (GUILayout.Button("Start Host (서버+클라 한 인스턴스)"))
                {
                    _connection.StartHost(new ConnectionPayload
                    {
                        userId = Random.Range(1, 100000),
                        nickname = _nickname,
                        avatarCode = AvatarAppearance.DefaultPreset,
                        connectionToken = "poc-dummy-token"
                    });
                }

                if (GUILayout.Button("Start Server (로컬 테스트용)"))
                    nm.StartServer();

                if (GUILayout.Button("Connect as Client (직접 입력)"))
                {
                    ushort.TryParse(_port, out var port);
                    _connection.StartClient(_address, port == 0 ? (ushort)7777 : port, new ConnectionPayload
                    {
                        userId = Random.Range(1, 100000),
                        nickname = _nickname,
                        // Full modular data is sent after the owner player spawns
                        // (PlayerAppearanceController.ApplySceneHandoff). Keeping
                        // connection approval payload short prevents transport and
                        // legacy FixedString truncation.
                        avatarCode = AvatarAppearance.DefaultPreset,
                        // POC 더미 토큰. 실제로는 Spring world-sessions 응답 토큰 사용.
                        connectionToken = "poc-dummy-token"
                    });
                }

                if (GUILayout.Button("Connect via Session API"))
                    ConnectViaSessionApi();
            }
            else
            {
                GUILayout.Label(nm.IsServer ? $"SERVER — clients: {nm.ConnectedClientsIds.Count}"
                                            : "CLIENT — connected");
                if (GUILayout.Button("Disconnect"))
                {
                    WorldReconnector.MarkUserInitiatedShutdown();   // 사용자가 끊는 것 — 자동 재접속 대상 아님 (-432)
                    _connection.Shutdown();
                }

                if (nm.IsClient && !nm.IsServer && GUILayout.Button("커스터마이징으로 돌아가기"))
                    ReturnToCustomization(nm);
            }

            GUILayout.EndArea();
        }
#endif

        /// <summary>
        /// 정식 접속 흐름 검증: world-sessions API(현재 Mock) → endpoint/token → StartClient.
        /// AWS 배포 후에는 응답의 scheme만 wss로 바뀌면 그대로 동작해야 한다.
        /// </summary>
        async void ConnectViaSessionApi()
        {
            // 이미 서버/클라이언트로 떠 있으면 StartClient 를 걸지 않는다.
            // NetworkManager 는 싱글턴이라 겹쳐 요청하면 전송 계층이
            // "Failed to connect to server." 만 남기고 원인을 알려주지 않는다 (T-182).
            var running = Unity.Netcode.NetworkManager.Singleton;
            if (running != null && (running.IsListening || running.IsClient))
            {
                Debug.LogWarning(
                    $"[DevConnectionHud] 이미 네트워크가 떠 있어 접속 요청을 건너뛴다 " +
                    $"(IsServer={running.IsServer} IsClient={running.IsClient}). " +
                    "로비에서 넘어왔는데 이 인스턴스가 서버로 시작된 경우다.");
                return;
            }

            ApiServices.EnsureInitialized();
            var session = await ApiServices.User.CreateWorldSessionAsync();
            if (session == null)
            {
                Debug.LogError("[DevConnectionHud] world session 발급 실패");
                return;
            }

            Debug.Log($"[DevConnectionHud] session={session.sessionId} channel={session.channelId} " +
                      $"→ {session.endpoint.scheme}://{session.endpoint.host}:{session.endpoint.port}");

            _connection.StartClient(session, new ConnectionPayload
            {
                userId = Random.Range(1, 100000),
                nickname = _nickname,
                avatarCode = AvatarAppearance.DefaultPreset
            });
        }

        void ReturnToCustomization(Unity.Netcode.NetworkManager networkManager)
        {
            var player = networkManager.LocalClient?.PlayerObject;
            var appearance = player ? player.GetComponent<PlayerAppearanceController>() : null;
            if (appearance != null) AvatarSceneHandoff.Save(appearance.Current);

            WorldReconnector.MarkUserInitiatedShutdown();   // 사용자가 끊는 것 — 자동 재접속 대상 아님 (-432)
            _connection.Shutdown();
            SceneManager.LoadScene(AvatarSceneHandoff.LobbySceneName);
        }
    }
}
