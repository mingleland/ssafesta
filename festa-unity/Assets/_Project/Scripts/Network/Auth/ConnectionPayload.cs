using System;

namespace Festa.Network
{
    /// <summary>
    /// Client → Server Connection Approval 시 전달되는 페이로드.
    /// NetworkConfig.ConnectionData 에 UTF-8 JSON으로 직렬화된다.
    /// connectionToken은 향후 Spring POST /api/v1/world-sessions 응답의
    /// short-lived token으로 교체된다. Refresh Token은 절대 Unity로 전달하지 않는다.
    /// </summary>
    [Serializable]
    public class ConnectionPayload
    {
        public long userId;
        public string nickname;
        public string avatarCode;
        public string connectionToken;
    }
}
