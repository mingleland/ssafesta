using System;

namespace Festa.Integration
{
    /// <summary>
    /// World 접속 endpoint (deployment-handoff §3 계약).
    /// full URI가 아닌 구조화 필드 — UnityTransport API(host/port)에 직접 매핑된다.
    /// path는 UnityTransport WebSocket이 지원하지 않으므로 계약에서 제외.
    /// </summary>
    [Serializable]
    public class WorldEndpointDto
    {
        public string scheme; // "ws"(로컬/개발) | "wss"(배포 — LB에서 TLS 종료)
        public string host;
        public int port;
    }

    /// <summary>
    /// doc 16 §3 World Session 응답 계약.
    /// MVP에서는 항상 단일 채널(11F-01)이 반환되지만,
    /// Unity는 endpoint를 하드코딩하지 않고 항상 이 응답을 사용한다 (Channel 확장 대비).
    /// </summary>
    [Serializable]
    public class WorldSessionDto
    {
        public string sessionId;
        public string worldId;
        public string channelId;
        public WorldEndpointDto endpoint;
        public string connectionToken;
        public string expiresAt;
    }

    /// <summary>
    /// world-session endpoint를 UnityTransport의 host/port/TLS 설정으로 바꾼다.
    ///
    /// <para>개발용 <c>ws</c>는 localhost·직접 포트를 허용한다. 배포용 <c>wss</c>는
    /// 인증서 이름 검증이 가능한 DNS host와 공개 포트 443만 허용한다. 이 경계에서
    /// 거부하면 Backend 설정 오류가 내부 7777 또는 IP 직접 접속으로 조용히 바뀌지 않는다.</para>
    /// </summary>
    public static class WorldSessionEndpoint
    {
        public static bool TryGetConnectionData(WorldSessionDto session, out string host,
                                                out ushort port, out bool useTls)
        {
            host = null;
            port = 0;
            useTls = false;

            var endpoint = session?.endpoint;
            if (endpoint == null || string.IsNullOrWhiteSpace(endpoint.host) ||
                endpoint.host != endpoint.host.Trim() || endpoint.port < 1 || endpoint.port > ushort.MaxValue)
                return false;

            bool ws = string.Equals(endpoint.scheme, "ws", StringComparison.OrdinalIgnoreCase);
            bool wss = string.Equals(endpoint.scheme, "wss", StringComparison.OrdinalIgnoreCase);
            if (!ws && !wss) return false;

            var hostKind = Uri.CheckHostName(endpoint.host);
            if (hostKind == UriHostNameType.Unknown) return false;

            if (wss && (hostKind != UriHostNameType.Dns ||
                        string.Equals(endpoint.host, "localhost", StringComparison.OrdinalIgnoreCase) ||
                        endpoint.port != 443)) return false;

            host = endpoint.host;
            port = (ushort)endpoint.port;
            useTls = wss;
            return true;
        }
    }
}
