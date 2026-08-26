using System;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 부하 테스트용 봇 클라이언트 (GitLab #89 2단계 / S15P21A604-164).
    ///
    /// 1단계(<see cref="AvatarStressSpawner"/>)는 **내 화면의 렌더·메모리 비용**을 접속 없이 쟀다.
    /// 대역폭은 그렇게 못 잰다 — 진짜 클라이언트가 서로 상태를 주고받아야 나온다.
    /// 이 컴포넌트는 실행 인자로 켜지는 봇 모드다.
    ///
    /// 실행 예 (빌드된 플레이어):
    ///   festa.exe -batchmode -nographics -bot -addr 127.0.0.1 -port 7777 -botName bot01
    ///
    /// 측정 방법 — 에디터를 **Host** 로 띄우고 봇 N기를 붙인 뒤 에디터에서 읽는다.
    ///   · 대역폭: RuntimeNetStatsMonitor (수신 bytes/s) — 인원수에 따른 증가가 목표 수치다
    ///   · 서버 프레임·메모리: PerfHud
    /// 봇은 화면이 없어도 서버에 이동 갱신을 보내므로 트래픽 모양이 실제와 같다.
    ///
    /// 메모리 절약: 봇은 아바타 외형을 만들지 않는다 (<see cref="IsBotProcess"/>).
    /// 노트북 한 대에서 여러 프로세스를 띄워야 하므로 1기당 메모리가 곧 가능한 봇 수다.
    /// </summary>
    public class LoadTestBot : MonoBehaviour
    {
        /// <summary>봇 프로세스인가. 외형 생성처럼 봇에 불필요한 작업을 건너뛰는 데 쓴다.</summary>
        public static bool IsBotProcess { get; private set; }

        [SerializeField] float _connectRetrySeconds = 3f;
        [Tooltip("이동 방향을 바꾸는 간격(초). 너무 짧으면 실제 사용자보다 트래픽이 과장된다.")]
        [SerializeField] float _turnInterval = 2.5f;
        [Tooltip("봇이 돌아다닐 반경(unit). 1 m = 10 unit")]
        [SerializeField] float _roamRadius = 150f;

        ConnectionManager _connection;
        Vector3 _origin;
        Vector3 _target;
        float _nextTurn;
        float _nextRetry;
        bool _requested;
        bool _running;

        void Awake()
        {
            if (!HasArg("-bot")) { enabled = false; return; }
            IsBotProcess = true;
            Application.targetFrameRate = 20;   // 봇은 그릴 게 없다 — CPU 를 서버/다른 봇에 양보
            QualitySettings.vSyncCount = 0;
            Debug.Log("[LoadTestBot] 봇 모드 활성");
        }

        void Start()
        {
            _connection = FindFirstObjectByType<ConnectionManager>();
            if (_connection == null)
                Debug.LogError("[LoadTestBot] ConnectionManager 를 찾지 못했다 — 씬 구성 확인");
        }

        void Update()
        {
            var nm = NetworkManager.Singleton;
            if (nm == null || _connection == null) return;

            if (!nm.IsClient)
            {
                if (Time.time < _nextRetry) return;
                _nextRetry = Time.time + _connectRetrySeconds;
                Connect();
                return;
            }

            DriveMovement();
            SampleAndLogMetrics();
        }

        void Connect()
        {
            string addr = GetArg("-addr", "127.0.0.1");
            ushort port = (ushort)GetArgInt("-port", 7777);
            string name = GetArg("-botName", "bot");

            var payload = new ConnectionPayload
            {
                userId = Math.Abs(name.GetHashCode()) % 1000000,
                nickname = name,
                avatarCode = "sk_01",
                // POC 승인 규칙은 "비어 있지 않으면 통과" 다 — 부하 테스트도 같은 경로를 탄다.
                connectionToken = "loadtest",
            };
            bool ok = _connection.StartClient(addr, port, payload);
            Debug.Log($"[LoadTestBot] {name} → ws://{addr}:{port} 접속 시도 = {ok}");
            _requested = true;
        }

        /// <summary>
        /// 소유한 플레이어를 돌아다니게 한다. 정지해 있으면 NetworkTransform 이 아무것도
        /// 보내지 않아 **트래픽이 0 으로 측정된다** — 부하 테스트의 의미가 사라진다.
        /// </summary>
        void DriveMovement()
        {
            var nm = NetworkManager.Singleton;
            var player = nm.LocalClient?.PlayerObject;
            if (player == null) return;

            var movement = player.GetComponent<PlayerMovement>();
            if (movement == null) return;

            var tr = player.transform;
            if (_origin == Vector3.zero) { _origin = tr.position; _target = tr.position; }

            if (Time.time >= _nextTurn)
            {
                _nextTurn = Time.time + _turnInterval;
                var offset = UnityEngine.Random.insideUnitCircle * _roamRadius;
                _target = _origin + new Vector3(offset.x, 0f, offset.y);
                // 다섯에 하나는 달리게 한다 — 걷기/달리기 전환도 AnimState 를 바꿔 트래픽을 만든다.
                _running = UnityEngine.Random.value < 0.2f;
            }

            // **입력을 주입한다.** CharacterController 를 직접 밀면 PlayerMovement 를
            // 건너뛰어 AnimState 가 Idle 에 머물고, 걷기 애니메이션이 원격에 전파되지 않아
            // 애니메이션 동기화 트래픽과 원격 아바타 CPU 가 통째로 빠진다.
            // 실제 사용자와 같은 경로를 타야 측정이 유효하다.
            movement.UseSyntheticInput = true;

            var to = _target - tr.position;
            to.y = 0f;
            if (to.sqrMagnitude < 9f)
            {
                movement.SyntheticInput = Vector2.zero;   // 도착 — 잠시 Idle (실제 사용자도 멈춘다)
                return;
            }

            // PlayerMovement 는 입력을 카메라 기준으로 회전시킨다. 봇에는 카메라가 없어
            // 폴백 경로(월드 기준)를 타므로 방향을 그대로 넣으면 된다.
            var dir = to.normalized;
            movement.SyntheticInput = new Vector2(dir.x, dir.z);
            movement.SyntheticRun = _running;
        }


        // ── 봇 자신의 수신 대역폭 로깅 ──
        // 서버(Host)에서 읽는 값은 **전 클라이언트 업로드 합계**다. 정작 궁금한 것은
        // "브라우저 한 대가 얼마나 받는가" — 그건 클라이언트에서만 보인다.
        // 봇이 주기적으로 자기 수신량을 로그에 남기면 그 값을 그대로 쓸 수 있다.
        object _metrics;
        System.Reflection.FieldInfo _recvField;
        System.Reflection.PropertyInfo _counterValue;
        long _recvAccum;
        float _metricWindow;
        float _nextMetricLog;

        void SampleAndLogMetrics()
        {
            var nm = NetworkManager.Singleton;
            if (nm == null || !nm.IsClient) return;

            if (_metrics == null)
            {
                const System.Reflection.BindingFlags BF =
                    System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic |
                    System.Reflection.BindingFlags.Instance;
                _metrics = nm.GetType().GetProperty("NetworkMetrics", BF)?.GetValue(nm);
                if (_metrics == null) return;
                _recvField = _metrics.GetType().GetField("m_TransportBytesReceived", BF);
                var counter = _recvField?.GetValue(_metrics);
                _counterValue = counter?.GetType().GetProperty("Value", BF);
            }
            if (_counterValue == null) return;

            // 카운터는 매 프레임 dispatch 후 리셋된다 — 프레임마다 더해야 초당 값이 나온다.
            _recvAccum += (long)_counterValue.GetValue(_recvField.GetValue(_metrics));
            _metricWindow += Time.unscaledDeltaTime;

            if (_metricWindow >= 1f)
            {
                float kbs = _recvAccum / _metricWindow / 1024f;
                _recvAccum = 0; _metricWindow = 0f;
                if (Time.time >= _nextMetricLog)
                {
                    _nextMetricLog = Time.time + 5f;   // 5초마다 한 줄 — 로그가 부풀지 않게
                    Debug.Log($"[LoadTestBot][metrics] 수신 {kbs:F2} KB/s");
                }
            }
        }

        // ── 실행 인자 ──

        static bool HasArg(string name)
        {
            foreach (var a in Environment.GetCommandLineArgs())
                if (string.Equals(a, name, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        static string GetArg(string name, string fallback)
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
            return fallback;
        }

        static int GetArgInt(string name, int fallback)
            => int.TryParse(GetArg(name, null), out var v) ? v : fallback;
    }
}
