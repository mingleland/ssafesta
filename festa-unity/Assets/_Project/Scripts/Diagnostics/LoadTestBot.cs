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

            var tr = player.transform;
            if (_origin == Vector3.zero) { _origin = tr.position; _target = tr.position; }

            if (Time.time >= _nextTurn)
            {
                _nextTurn = Time.time + _turnInterval;
                var offset = UnityEngine.Random.insideUnitCircle * _roamRadius;
                _target = _origin + new Vector3(offset.x, 0f, offset.y);
            }

            var to = _target - tr.position;
            to.y = 0f;
            if (to.sqrMagnitude < 4f) return;

            // PlayerMovement 는 Owner 전용 키보드 입력을 읽는다. 봇에는 키보드가 없으므로
            // CharacterController 로 직접 민다 — 이동 권한이 client-authoritative 라
            // 결과 위치가 그대로 복제되고, 서버로 나가는 트래픽 모양은 사람과 같다.
            var cc = player.GetComponent<CharacterController>();
            var dir = to.normalized;
            if (cc != null && cc.enabled) cc.SimpleMove(dir * 40f);
            else tr.position += dir * 40f * Time.deltaTime;
            tr.rotation = Quaternion.LookRotation(dir);
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
