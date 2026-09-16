// 끊긴 접속의 마지막 위치를 신원(subject)별로 잠시 들고 있다가 같은 신원이 재접속하면 돌려주는 게임 서버 메모리 (GitLab #200 B안).
using System.Collections.Generic;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 재접속 위치 복원 — <b>게임 서버 메모리 한정</b>.
    ///
    /// <para><b>배경.</b> 탭을 뒤로 보내면 rAF 가 멈춰 NGO 하트비트가 끊기고, 30초 뒤 서버가 접속을 정리한다.
    /// <c>WorldReconnector</c> 가 새 world-session 으로 다시 붙으면 승인(<c>ConnectionManager.Approve</c>)이 항상
    /// 슬롯 스폰 좌표를 배정하므로 서 있던 자리를 잃는다(#200). BE·FE 가 B안(게임 서버가 같은 신원의 마지막 좌표를
    /// 짧게 보존)을 지지했고, 키는 <c>WorldSessionRegistry</c> 가 이미 쥔 grant <c>subject</c> 다 — 회원은 userId,
    /// 게스트는 토큰 subject. 재접속 전후로 변하지 않는다(BE 확인).</para>
    ///
    /// <para><b>동결 준수.</b> <c>ConnectionManager.cs</c>(승인)·<c>NetworkPlayer.cs</c> 는 건드리지 않는다.
    /// 기록은 플레이어 오브젝트의 서버측 <c>OnNetworkDespawn</c>, 복원은 <c>PlayerMovement</c> 의 서버측
    /// <c>OnNetworkSpawn</c>(<c>ServerSpawnPosition</c> 덮어쓰기)에서 한다 — 승인 응답의 좌표는 그대로 두고
    /// 그 뒤에 Owner 가 스스로 텔레포트하는 기존 절차(T-177)를 그대로 탄다.</para>
    ///
    /// <para><b>영속하지 않는다.</b> 서버 재시작을 넘겨 복원하지 않는다(#131 "복원할 이전 세션은 서버에 없다" 와
    /// 헌법 12조 게스트 비영속을 지키는 선). 보존 창은 <see cref="RetainSeconds"/> — 클라 disconnect 30초 +
    /// 재접속 백오프 합 ~67초를 넉넉히 덮는다.</para>
    ///
    /// <para><b>경합.</b> 같은 신원이 헌 접속이 정리되기 전에 새로 붙으면(<c>ReplacedBySameUser</c>) 새 스폰이 먼저,
    /// 헌 despawn 의 기록이 나중일 수 있다. 그래서 새 스폰은 <see cref="WatchLateArrival"/> 로 몇 초간 귀를 열어 두고,
    /// 그 사이 같은 subject 의 기록이 들어오면 <see cref="LateArrival"/> 로 넘겨 Owner 에게 텔레포트를 보낸다.</para>
    /// </summary>
    public static class PlayerPositionMemory
    {
        public const float RetainSeconds = 180f;
        /// <summary>새 스폰이 헌 접속의 늦은 기록을 기다리는 시간.</summary>
        public const float LateArrivalWindowSeconds = 5f;

        public struct Entry
        {
            public Vector3 Position;
            public float Yaw;
            public double SavedAt;
        }

        static readonly Dictionary<string, Entry> s_entries = new();
        static readonly Dictionary<string, (System.Action<Entry> callback, double until)> s_watchers = new();

        static double Now => Time.realtimeSinceStartupAsDouble;

        /// <summary>끊긴 접속의 마지막 자리를 기록한다. 같은 subject 를 기다리는 새 스폰이 있으면 즉시 넘긴다.</summary>
        public static void Remember(string subject, Vector3 position, float yaw)
        {
            if (string.IsNullOrEmpty(subject)) return;
            if (position == Vector3.zero) return;   // 원점은 방 밖 허공 — 배치 전 값이다
            Prune();

            var entry = new Entry { Position = position, Yaw = yaw, SavedAt = Now };
            if (s_watchers.TryGetValue(subject, out var watcher) && watcher.until >= Now)
            {
                s_watchers.Remove(subject);
                Debug.Log($"[PlayerPositionMemory] sub={subject} 헌 접속 기록이 새 스폰 뒤에 도착 — 늦은 복원으로 넘긴다 {position}");
                watcher.callback?.Invoke(entry);
                return;
            }

            s_entries[subject] = entry;
            Debug.Log($"[PlayerPositionMemory] sub={subject} 마지막 위치 보관 {position} yaw={yaw:F0} ({RetainSeconds:F0}s)");
        }

        /// <summary>같은 신원의 보관된 자리를 꺼낸다(한 번만). 만료됐거나 없으면 false.</summary>
        public static bool TryTake(string subject, out Entry entry)
        {
            entry = default;
            if (string.IsNullOrEmpty(subject)) return false;
            Prune();
            if (!s_entries.TryGetValue(subject, out entry)) return false;
            s_entries.Remove(subject);
            return true;
        }

        /// <summary>새 스폰이 몇 초 동안 같은 subject 의 늦은 기록을 기다린다. 기록이 오면 <paramref name="onArrival"/> 가 불린다.</summary>
        public static void WatchLateArrival(string subject, System.Action<Entry> onArrival)
        {
            if (string.IsNullOrEmpty(subject) || onArrival == null) return;
            s_watchers[subject] = (onArrival, Now + LateArrivalWindowSeconds);
        }

        public static void Unwatch(string subject)
        {
            if (!string.IsNullOrEmpty(subject)) s_watchers.Remove(subject);
        }

        public static int Count => s_entries.Count;

        /// <summary>서버 종료·테스트용.</summary>
        public static void Clear() { s_entries.Clear(); s_watchers.Clear(); }

        static readonly List<string> s_expired = new();
        static void Prune()
        {
            double now = Now;
            s_expired.Clear();
            foreach (var kv in s_entries) if (now - kv.Value.SavedAt > RetainSeconds) s_expired.Add(kv.Key);
            foreach (var k in s_expired) s_entries.Remove(k);
            s_expired.Clear();
            foreach (var kv in s_watchers) if (kv.Value.until < now) s_expired.Add(kv.Key);
            foreach (var k in s_expired) s_watchers.Remove(k);
        }
    }
}
