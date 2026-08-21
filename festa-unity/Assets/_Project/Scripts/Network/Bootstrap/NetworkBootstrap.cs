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

#if UNITY_SERVER
            StartDedicatedServer(transport);
#else
            if (Application.isBatchMode || HasArg("-server"))
            {
                StartDedicatedServer(transport);
            }
            // 그 외에는 DevConnectionHud가 수동 시작을 담당한다.
#endif
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
