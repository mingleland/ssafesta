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

        void Start()
        {
            var transport = NetworkManager.Singleton.GetComponent<UnityTransport>();

            // 브라우저는 UDP 소켓을 열 수 없으므로 WebSocket을 모든 환경에서 강제한다.
            // 배포 시 브라우저는 wss:// → ALB(TLS 종료) → ws:// 서버 순서로 연결된다.
            transport.UseWebSockets = true;

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

        void StartDedicatedServer(UnityTransport transport)
        {
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
