using System.Collections.Generic;

namespace Festa.Network
{
    /// <summary>
    /// 서버 전용. Connection Approval에서 검증된 페이로드를 clientId로 보관했다가
    /// NetworkPlayer 스폰 시 초기값(nickname/avatarCode)을 공급한다.
    /// BossRoom SessionManager의 최소화 버전 — 재접속 데이터 보존은 정책 확정 후 추가한다.
    /// </summary>
    public static class SessionDataStore
    {
        static readonly Dictionary<ulong, ConnectionPayload> s_ByClientId = new();

        public static void Set(ulong clientId, ConnectionPayload payload) => s_ByClientId[clientId] = payload;

        public static ConnectionPayload Get(ulong clientId) =>
            s_ByClientId.TryGetValue(clientId, out var payload) ? payload : null;

        public static void Remove(ulong clientId) => s_ByClientId.Remove(clientId);

        public static void Clear() => s_ByClientId.Clear();
    }
}
