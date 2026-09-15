using System;
using Unity.Netcode;
using Unity.Netcode.Transports.UTP;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// NetworkManager와 같은 오브젝트에 부착한다.
    /// - Transport를 WebSocket으로 강제한다 (Web 빌드 필수, 데스크톱/서버도 동일 프로토콜로 통일)
    /// - Dedicated Server 빌드/배치 모드에서 자동으로 StartServer 한다
    /// - CLI 인자(-port, -maxPlayers)를 파싱한다 (향후 ECS Task 환경변수 대응 지점)
    /// </summary>
    [RequireComponent(typeof(NetworkManager))]
    public class NetworkBootstrap : MonoBehaviour
    {
        [Header("Server Defaults")]
        [SerializeField] ushort _defaultPort = 7777;
        [SerializeField] int _maxPlayers = 40;

        public int MaxPlayers => _maxPlayers;

        /// <summary>NGO 비분할 메시지 상한(바이트). 연결 요청 payload(회원 grant 1.1 KB + JSON)가 들어가야 한다 (S15P21A604-457).</summary>
        public const int ConnectionRequestMtu = 4096;

        void Start()
        {
            var transport = NetworkManager.Singleton.GetComponent<UnityTransport>();

            // 브라우저는 UDP 소켓을 열 수 없으므로 WebSocket을 모든 환경에서 강제한다.
            // 배포 시 브라우저는 wss:// → ALB(TLS 종료) → ws:// 서버 순서로 연결된다.
            transport.UseWebSockets = true;

            // 연결 요청(ConnectionData)은 분할되지 않는 메시지라 NGO 의 MTU(기본 1,296 → payload 약 1,114 B)가 상한이다.
            // Spring 의 회원 grant 는 1,094자(avatarCode 클레임 411자 포함)여서 JSON payload 가 1,172 B — 회원은 접속조차
            // 못 했다(OverflowException, 2026-09-06 실측, S15P21A604-457 / T-125). 4,096 으로 올린다 — UnityTransport 는
            // 자기 MTU 를 넘는 payload 를 스스로 분할·재조립하므로(FragmentationPipelineStage) 서버가 옛 이미지여도 받는다.
            // 값은 8 의 배수로 내림된다. MessageManager 는 Start* 안(Initialize)에서 만들어지므로 그 전에 대면 NRE —
            // Start* 끝에 오는 OnClientStarted/OnServerStarted 에서 대면 전송 연결(→ 연결 요청 송신)보다 앞선다.
            NetworkManager.Singleton.OnClientStarted += ApplyConnectionRequestMtu;
            NetworkManager.Singleton.OnServerStarted += ApplyConnectionRequestMtu;

            // 자동 시작 조건 — **에디터에서는 자동으로 서버가 되지 않는다.**
            //
            // 전에는 `#if UNITY_SERVER` 만 보고 시작했는데, 빌드 타깃을 Dedicated Server 로
            // 두면 그 심볼이 **에디터에도 정의된다.** 그러면 Play 를 누르는 순간 에디터가
            // 서버가 되고, 로비에서 넘어온 핸드오프가 같은 NetworkManager 에 StartClient 를
            // 걸어 "Failed to connect to server." 로 끝난다 — 원인이 전혀 드러나지 않는
            // 형태였다 (T-182). 에디터에서 서버를 띄우려면 DevConnectionHud 의
            // "Start Server" 버튼이나 `-server` 인자를 쓴다.
            //
            // ⚠ 그 수정이 만든 두 번째 함정 (T-202, 2026-08-25):
            // `!Application.isEditor` 는 **모든 플레이어 빌드**를 서버로 만든다 — 브라우저
            // (WebGL)와 부하 테스트 봇까지. 봇은 자기 자신이 서버로 떠서 StartClient 가
            // 전부 실패했고, 원인이 로그 없이는 보이지 않았다.
            //   · WebGL: 브라우저는 소켓을 열 수 없어 서버가 될 수 없다 — 항상 클라이언트다.
            //   · 봇(-bot): 클라이언트로만 붙어야 트래픽 모양이 실제 사용자와 같다.
            bool botMode = HasArg("-bot");
            bool isRealServerBuild = !Application.isEditor;
#if UNITY_WEBGL
            isRealServerBuild = false;
#endif
            if (!botMode && (isRealServerBuild || Application.isBatchMode || HasArg("-server")))
            {
                StartDedicatedServer(transport);
                return;
            }

            Debug.Log(botMode
                ? "[NetworkBootstrap] 봇 모드 — 서버로 뜨지 않는다. LoadTestBot 이 클라이언트로 접속한다."
                : "[NetworkBootstrap] 자동 시작하지 않는다 — DevConnectionHud 로 Host/Server/Client 를 고른다.");
        }

        static void ApplyConnectionRequestMtu()
        {
            var nm = NetworkManager.Singleton;
            if (nm == null) return;
            try
            {
                if (nm.MaximumTransmissionUnitSize >= ConnectionRequestMtu) return;
                nm.MaximumTransmissionUnitSize = ConnectionRequestMtu;
                Debug.Log($"[NetworkBootstrap] 비분할 메시지 상한 {ConnectionRequestMtu} B — 회원 grant(1.1 KB) 연결 요청 수용 (S15P21A604-457)");
            }
            catch (Exception ex)
            {
                // 여기서 실패하면 회원 접속이 T-125 로 되돌아간다 — 조용히 넘기지 않는다.
                Debug.LogError($"[NetworkBootstrap] MTU 설정 실패: {ex.Message}");
            }
        }

        void StartDedicatedServer(UnityTransport transport)
        {
            // 소켓을 열기 **전에** 입장 검증 준비를 확인한다 (spec infra-003 world-entry-token.md).
            // 키가 없으면 여기서 프로세스를 끝낸다 — 검증 없이 뜬 서버는 아무나 들어온다.
            // 원장을 못 쓰면 뜨긴 하되 모든 입장이 fail-closed 로 거부되고, 그 사실을 로그로 드러낸다.
            WorldEntryTokenSecret.EnforceOrQuit();
            GrantReplayLedger.Warmup();

            ushort port = GetArgValue("-port", _defaultPort);
            _maxPlayers = (int)GetArgValue("-maxPlayers", (ushort)_maxPlayers);

            // 0.0.0.0: 컨테이너 내부에서 모든 인터페이스 바인딩
            transport.SetConnectionData("0.0.0.0", port, "0.0.0.0");

            bool ok = NetworkManager.Singleton.StartServer();
            Debug.Log($"[NetworkBootstrap] Dedicated server start={ok} port={port} maxPlayers={_maxPlayers} (WebSocket)");
        }

        static bool HasArg(string name)
        {
            foreach (var arg in Environment.GetCommandLineArgs())
                if (string.Equals(arg, name, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        static ushort GetArgValue(string name, ushort fallback)
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase) &&
                    ushort.TryParse(args[i + 1], out var value))
                    return value;
            return fallback;
        }
    }
}
