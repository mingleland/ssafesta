using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 월드 접속이 **내 뜻과 무관하게** 끊기면 자동으로 다시 붙는다 (GitLab #131, S15P21A604-432).
    ///
    /// 왜 필요한가 — 브라우저 탭을 뒤로 보내면 Chrome 이 requestAnimationFrame 을 멈추고, Unity WebGL
    /// 메인 루프가 rAF 로 도니 NGO 하트비트가 끊긴다(서버 10초 승인 버퍼 / 클라 30초 DisconnectTimeout).
    /// 사용자가 다른 탭을 잠깐 보는 것은 정상 행동이라, 돌아왔을 때 아무 안내 없이 끊긴 채면 안 된다(T-120).
    ///
    /// 동작:
    ///   1. 로컬 클라이언트 끊김 감지 → 호스트에 <c>onWorldConnectionState('disconnected', 사유)</c>
    ///   2. 사유가 재시도해도 소용없는 것(정원 초과·다른 곳에서 같은 계정 접속)이면 <c>'failed'</c> 로 끝
    ///   3. 그 외는 2·5·10초 뒤 최대 3회 — 매번 <b>새 world-session</b> 을 받아(grant 는 jti 1회용, -85)
    ///      <see cref="ConnectionManager.StartClient(Festa.Integration.WorldSessionDto, ConnectionPayload)"/>
    ///   4. 붙으면 <c>'connected'</c>, 3회 다 실패하면 <c>'failed'</c>
    ///
    /// 사용자가 직접 끊은 것(Disconnect 버튼·로비로 돌아가기)은 재시도하지 않는다 —
    /// 끊기 전에 <see cref="MarkUserInitiatedShutdown"/> 을 부른다.
    ///
    /// 서버·에디터 호스트에서는 아무 일도 하지 않는다. 씬을 고치지 않는다 — 자동 등록 오브젝트다.
    /// 아바타 외형은 <see cref="Festa.World.AvatarSceneHandoff"/> 에 남아 있어 재스폰 시 그대로 복원된다.
    /// </summary>
    public sealed class WorldReconnector : MonoBehaviour
    {
        public const string ObjectName = "WorldReconnector";
        static readonly float[] BackoffSeconds = { 2f, 5f, 10f };

        static bool s_userInitiated;

        /// <summary>사용자가 스스로 끊기 직전에 부른다 — 그 끊김은 재접속하지 않는다.</summary>
        public static void MarkUserInitiatedShutdown() => s_userInitiated = true;

        NetworkManager _subscribed;
        bool _reconnecting;
        bool _inFlight;
        int _attempt;
        float _nextAttemptAt;
        string _lastReason = "";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<WorldReconnector>();
            DontDestroyOnLoad(go);
#endif
        }

        void Update()
        {
            var nm = NetworkManager.Singleton;
            if (nm != _subscribed)
            {
                if (_subscribed != null) _subscribed.OnConnectionEvent -= OnConnectionEvent;
                _subscribed = nm;
                if (_subscribed != null) _subscribed.OnConnectionEvent += OnConnectionEvent;
            }

            if (_reconnecting && !_inFlight && Time.realtimeSinceStartup >= _nextAttemptAt)
                _ = TryReconnectAsync();
        }

        void OnDestroy()
        {
            if (_subscribed != null) _subscribed.OnConnectionEvent -= OnConnectionEvent;
        }

        void OnConnectionEvent(NetworkManager nm, ConnectionEventData data)
        {
            if (nm.IsServer) return;

            if (data.EventType == ConnectionEvent.ClientConnected && data.ClientId == nm.LocalClientId)
            {
                if (_reconnecting)
                {
                    Debug.Log($"[WorldReconnector] 재접속 성공 ({_attempt}회차)");
                    _reconnecting = false;
                    _attempt = 0;
                }
                Notify("connected", "");
                return;
            }

            if (data.EventType != ConnectionEvent.ClientDisconnected || data.ClientId != nm.LocalClientId) return;

            _lastReason = nm.DisconnectReason ?? "";

            if (s_userInitiated)
            {
                s_userInitiated = false;
                _reconnecting = false;
                Notify("disconnected", "USER");
                return;
            }

            // 재시도해도 결과가 같은 사유는 바로 실패로 드러낸다.
            if (_lastReason == WorldDisconnectReason.ServerFull ||
                _lastReason == WorldDisconnectReason.ReplacedBySameUser)
            {
                _reconnecting = false;
                Notify("failed", _lastReason);
                return;
            }

            if (_reconnecting)
            {
                // 재접속 시도 중에 또 끊겼다 — 다음 회차로.
                _attempt++;
                if (_attempt >= BackoffSeconds.Length)
                {
                    Debug.LogWarning($"[WorldReconnector] {BackoffSeconds.Length}회 재접속 실패 — 포기 (마지막 사유 '{_lastReason}')");
                    _reconnecting = false;
                    Notify("failed", _lastReason);
                    return;
                }
                _nextAttemptAt = Time.realtimeSinceStartup + BackoffSeconds[_attempt];
                Notify("reconnecting", (_attempt + 1).ToString());
                return;
            }

            Debug.LogWarning($"[WorldReconnector] 접속 끊김 (사유 '{_lastReason}') — {BackoffSeconds[0]}초 뒤 재접속 시도");
            _reconnecting = true;
            _attempt = 0;
            _nextAttemptAt = Time.realtimeSinceStartup + BackoffSeconds[0];
            Notify("disconnected", _lastReason);
        }

        async Task TryReconnectAsync()
        {
            _inFlight = true;
            try
            {
                var nm = NetworkManager.Singleton;
                if (nm == null || nm.IsListening || nm.IsClient) { _nextAttemptAt = Time.realtimeSinceStartup + 1f; return; }   // 이미 붙었거나 붙는 중 — 1초 뒤 다시 본다

                var connection = FindAnyObjectByType<ConnectionManager>();
                if (connection == null)
                {
                    Debug.LogError("[WorldReconnector] ConnectionManager 가 없어 재접속할 수 없다");
                    _reconnecting = false;
                    Notify("failed", "NO_CONNECTION_MANAGER");
                    return;
                }

                Notify("reconnecting", (_attempt + 1).ToString());
                Festa.Integration.ApiServices.EnsureInitialized();
                var session = await Festa.Integration.ApiServices.User.CreateWorldSessionAsync();
                if (session == null)
                {
                    // 토큰 만료·서버 불가 등. 다음 회차로 넘긴다 — 끊김 이벤트가 오지 않으니 여기서 세야 한다.
                    _attempt++;
                    if (_attempt >= BackoffSeconds.Length)
                    {
                        Debug.LogWarning("[WorldReconnector] world-session 재발급이 계속 실패 — 포기");
                        _reconnecting = false;
                        Notify("failed", "WORLD_SESSION_UNAVAILABLE");
                        return;
                    }
                    _nextAttemptAt = Time.realtimeSinceStartup + BackoffSeconds[_attempt];
                    return;
                }

                Debug.Log($"[WorldReconnector] 재접속 {_attempt + 1}회차 — session={session.sessionId}");
                bool ok = connection.StartClient(session, new ConnectionPayload
                {
                    userId = 0, nickname = "reconnect", avatarCode = Festa.World.AvatarAppearance.DefaultPreset,
                });
                if (!ok)
                {
                    _attempt++;
                    if (_attempt >= BackoffSeconds.Length) { _reconnecting = false; Notify("failed", "START_CLIENT_FAILED"); return; }
                    _nextAttemptAt = Time.realtimeSinceStartup + BackoffSeconds[_attempt];
                }
                // 성공/실패는 OnConnectionEvent 가 이어서 판정한다.
            }
            finally
            {
                _inFlight = false;
            }
        }

        // ── 호스트 알림 ─────────────────────────────────────────
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyWorldConnectionState(string state, string detail);
#endif

        static void Notify(string state, string detail)
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try { FestaNotifyWorldConnectionState(state, detail ?? ""); }
            catch (System.Exception ex) { Debug.LogError($"[WorldReconnector] onWorldConnectionState 송신 실패: {ex.Message}"); }
#else
            Debug.Log($"[WorldReconnector] onWorldConnectionState({state}, '{detail}') → (에디터: 송신 생략)");
#endif
        }
    }
}
