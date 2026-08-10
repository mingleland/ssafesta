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
        public string serverEndpoint; // "host:port" — 정확한 형식은 Transport POC 후 확정
        public string connectionToken;
        public string expiresAt;
    }
}
