using System.Collections.Generic;

namespace Festa.Network
{
    /// <summary>
    /// 서버 전용. **한 신원당 접속 하나**를 강제한다 (docs/12 §9 — "Player 중복 Spawn 방지").
    ///
    /// 왜 이제야 가능한가 — 이전에는 신원을 클라이언트가 보낸 payload 에서 읽었다. 아무나
    /// 아무 `userId` 나 적어 보낼 수 있었으니 "같은 사람인가" 를 물어볼 근거가 없었다.
    /// `S15P21A604-85` 로 grant 서명을 검증하면서 `sub` 클레임이 **서버가 만든 신원**이 됐고,
    /// 그제서야 중복을 판정할 수 있게 됐다.
    ///
    /// **정책은 나중 접속이 이긴다(last-writer-wins).** 반대로 하면 — 클라이언트가 죽었는데
    /// 서버가 아직 그 연결을 살아 있다고 보는 동안 — **본인이 자기 계정에 못 들어온다.**
    /// 전송 계층 타임아웃까지 수십 초를 기다려야 하고, 사용자 눈에는 그냥 "접속이 안 된다" 다.
    /// 신원이 서명으로 검증되므로 남이 내 자리를 밀어낼 수는 없다.
    /// </summary>
    public static class WorldSessionRegistry
    {
        static readonly Dictionary<string, ulong> s_byIdentity = new Dictionary<string, ulong>();
        static readonly Dictionary<ulong, string> s_byClient = new Dictionary<ulong, string>();

        /// <summary>현재 등록된 접속 수. 진단·테스트용.</summary>
        public static int Count => s_byClient.Count;

        /// <summary>
        /// 접속을 등록한다.
        ///
        /// 같은 신원이 이미 붙어 있으면 <paramref name="staleClientId"/> 에 그 오래된 clientId 를
        /// 담아 true 를 돌려준다 — 호출부가 그 연결을 끊어야 한다. 새 접속은 **언제나 등록된다.**
        /// </summary>
        public static bool TryRegister(string subject, ulong clientId, out ulong staleClientId)
        {
            staleClientId = 0;
            if (string.IsNullOrEmpty(subject)) return false;

            bool hasStale = s_byIdentity.TryGetValue(subject, out var previous) && previous != clientId;
            if (hasStale)
            {
                staleClientId = previous;
                // 오래된 쪽의 역방향 항목을 먼저 지운다. 남겨두면 그 연결이 끊길 때
                // Remove(stale) 가 **새 접속의 신원 항목까지** 지워버린다 — 그러면 그 다음
                // 중복 접속이 감지되지 않는다.
                s_byClient.Remove(previous);
            }

            s_byIdentity[subject] = clientId;
            s_byClient[clientId] = subject;
            return hasStale;
        }

        /// <summary>
        /// 접속이 끊겼을 때 정리한다.
        ///
        /// **밀려난 연결이 뒤늦게 끊겨도 새 접속의 등록은 살아남는다** — 다만 그 보호는 여기가
        /// 아니라 <see cref="TryRegister"/> 에 있다. 거기서 이미 `s_byClient[previous]` 를 지웠기
        /// 때문에 뒤늦은 `Remove(previous)` 는 아래 첫 줄에서 그대로 빠져나간다.
        ///
        /// 처음에는 여기에도 "신원이 지금도 이 clientId 를 가리킬 때만 지운다" 는 방어 분기를
        /// 뒀었다. 변이 테스트로 확인해 보니 **그 분기는 도달하지 않았다** — 있으면 다음 사람이
        /// 그걸 보호 장치로 믿게 되므로 지웠다. 불변식을 지키는 곳은 한 군데면 된다.
        /// </summary>
        public static void Remove(ulong clientId)
        {
            if (!s_byClient.TryGetValue(clientId, out var subject)) return;
            s_byClient.Remove(clientId);
            s_byIdentity.Remove(subject);
        }

        /// <summary>clientId 에 묶인 신원. 없으면 null.</summary>
        public static string SubjectOf(ulong clientId) =>
            s_byClient.TryGetValue(clientId, out var subject) ? subject : null;

        /// <summary>서버 재시작·테스트용.</summary>
        public static void Clear()
        {
            s_byIdentity.Clear();
            s_byClient.Clear();
        }
    }
}
