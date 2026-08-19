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
            if (AvatarSceneHandoff.ConsumeWorldConnectionRequest())
                ConnectViaSessionApi();
        }

        void OnGUI()
        {
            if (Application.isBatchMode) return;

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
                    _connection.Shutdown();

                if (nm.IsClient && !nm.IsServer && GUILayout.Button("커스터마이징으로 돌아가기"))
                    ReturnToCustomization(nm);
            }

            GUILayout.EndArea();
        }

        /// <summary>
        /// 정식 접속 흐름 검증: world-sessions API(현재 Mock) → endpoint/token → StartClient.
        /// AWS 배포 후에는 응답의 scheme만 wss로 바뀌면 그대로 동작해야 한다.
        /// </summary>
        async void ConnectViaSessionApi()
        {
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

            _connection.Shutdown();
            SceneManager.LoadScene(AvatarSceneHandoff.LobbySceneName);
        }
    }
}
