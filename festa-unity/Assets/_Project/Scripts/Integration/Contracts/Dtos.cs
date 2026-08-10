using System;

namespace Festa.Integration
{
    /// <summary>doc 08 기준 사용자 프로필 최소 필드 (Draft)</summary>
    [Serializable]
    public class UserProfileDto
    {
        public long userId;
        public string nickname;
        public string avatarCode;
    }

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
}
