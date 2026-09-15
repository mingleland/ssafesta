using System.Collections.Generic;

namespace Festa.Booth
{
    /// <summary>
    /// 슬롯별 마지막 게시본 조회가 <b>확정</b>(서버가 200/404/409 로 답했다)이었는지 <b>일시 실패</b>(네트워크·타임아웃·예외)였는지.
    /// 왜 있는가: <c>WorldBoothPublishedBootstrap</c> 의 재시도 루프는 "채워지지 않은 방" 을 전부 다시 두드렸다.
    /// 그런데 미게시(404) 방도 채워지지 않은 방이라, 릴리스 <c>37d9b4f4</c> 실측에서 11실 × 6회 = 66번을 헛되이 다시 조회했다
    /// (2026-09-09, 20초마다 `아직 못 채운 방 11실 — 재시도 n/6`). 404 는 답이다 — 다시 물을 이유가 없다.
    /// 재시도는 <b>일시 실패였던 방</b>과 <b>아직 한 번도 답을 못 받은 방</b>에만 건다.
    /// </summary>
    public static class PublishedSlotResolution
    {
        // slotId → 일시 실패였는가. 없으면 "아직 모른다" = 재시도 대상.
        static readonly Dictionary<int, bool> s_transient = new();

        /// <summary>조회 결과를 기록한다. <paramref name="transientFailure"/> 가 false 면 서버가 답한 확정 결과다.</summary>
        public static void Set(int slotId, bool transientFailure) => s_transient[slotId] = transientFailure;

        /// <summary>확정 답을 받은 방은 false. 일시 실패했거나 아직 기록이 없으면 true.</summary>
        public static bool NeedsRetry(int slotId) => !s_transient.TryGetValue(slotId, out var transient) || transient;

        /// <summary>씬 전환(재입장) 때 비운다 — 새 세션은 새로 묻는다.</summary>
        public static void Clear() => s_transient.Clear();
    }
}
