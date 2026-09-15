using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 로컬 플레이어가 <b>부스 안에 있는가</b>를 판정해 호스트(React)에 알린다 (GitLab #174, S15P21A604-623).
    ///
    /// <para><b>왜 Unity 가 판정하나.</b> FE 가 가진 값 어느 것도 "부스 안" 을 뜻하지 않는다 — 초점 카메라는
    /// 밖에서도 켜지고, 상호작용 이력은 위치가 아니다. 틀린 추정으로 나가기 버튼이 엉뚱한 데 뜨면 F 로 나가던
    /// 사람까지 헷갈린다. 위치는 월드가 알고, 월드는 Unity 다.</para>
    ///
    /// <para><b>판정은 방 볼륨이다.</b> <c>Interior_NN</c> 방의 렌더러 경계 안에 서 있으면 그 부스 안이다.
    /// 포털 텔레포트만 보고 갈아 끼우면 재접속 스폰처럼 텔레포트 없이 위치가 바뀌는 경로에서 값이 어긋난다 —
    /// 텔레포트 직후에는 <see cref="Refresh"/> 로 즉시 갱신하고, 그 밖에는 짧은 주기로 다시 잰다.</para>
    ///
    /// <para><b>진입 직후 1회 + 바뀔 때만.</b> 전이만 보내면 재접속·재시도 boot 에서 FE 가 초기값을 모른다
    /// (AudioBridge 의 mute 동기화 -557 과 같은 이유). 로컬 플레이어가 생기면 현재 값을 한 번 보내고,
    /// 그 뒤는 안↔밖이 바뀔 때만 보낸다. 매 프레임 밀지 않는다.</para>
    ///
    /// <para><see cref="TryExitCurrentBooth"/> 는 호스트의 "나가기" 요청이 타는 길이다 — <b>F 와 같은 경로</b>
    /// (<see cref="PortalInteractor.TeleportThrough"/>)를 쓴다. 부스 밖이면 아무 일도 하지 않는다(멱등).</para>
    ///
    /// 로컬 표시 전용 — NetworkObject 없음.
    /// </summary>
    public class BoothContextPresenter : MonoBehaviour
    {
        /// <summary>안/밖을 다시 재는 주기(초). 텔레포트는 <see cref="Refresh"/> 가 즉시 반영하므로 이건 보험이다.</summary>
        const float SweepSeconds = 0.35f;

        public static bool Enabled = true;

        /// <summary>지금 들어가 있는 부스 번호. 밖이면 0.</summary>
        public static int CurrentBoothId { get; private set; }

        static BoothContextPresenter _instance;
        static bool s_dirty;

        readonly List<(int boothId, Bounds bounds, Transform returnPoint)> _rooms = new();
        bool _roomsResolved;
        bool _warnedNoRooms;
        bool _publishedOnce;
        float _nextSweep;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Enabled || _instance != null) return;
            var go = new GameObject("@BoothContextPresenter");
            DontDestroyOnLoad(go);
            _instance = go.AddComponent<BoothContextPresenter>();
        }

        /// <summary>텔레포트처럼 위치가 확실히 바뀐 직후 부른다 — 다음 프레임에 바로 다시 잰다.</summary>
        public static void Refresh() => s_dirty = true;

        /// <summary>
        /// 부스 안이면 그 부스의 <c>ReturnPoint_NN</c> 으로 내보낸다. 밖이면 false — 아무 일도 하지 않는다.
        /// </summary>
        public static bool TryExitCurrentBooth(out int boothId)
        {
            boothId = CurrentBoothId;
            if (boothId <= 0 || _instance == null) return false;

            var local = PortalInteractor.Local;
            if (local == null)
            {
                Debug.LogWarning("[BoothContextPresenter] 로컬 포털 인터랙터가 없어 퇴장을 못 한다 — 스폰 전이거나 접속이 끊긴 상태다.");
                return false;
            }

            Transform rp = null;
            foreach (var r in _instance._rooms) if (r.boothId == boothId) { rp = r.returnPoint; break; }
            if (rp == null) rp = GameObject.Find($"ReturnPoint_{boothId:00}")?.transform;
            if (rp == null)
            {
                Debug.LogError($"[BoothContextPresenter] ReturnPoint_{boothId:00} 이 씬에 없다 — 퇴장 목적지를 못 찾았다.");
                return false;
            }

            local.TeleportThrough(rp, portal: null);
            return true;
        }

        void Update()
        {
            // 주기 게이트가 맨 앞이다 — 방 탐색(GameObject.Find)도 여기 걸린다. 로비처럼 @BoothInteriors 가
            // 아예 없는 씬에서 매 프레임 Find 를 돌면 그냥 낭비다. 텔레포트는 s_dirty 로 이 게이트를 건너뛴다.
            if (!s_dirty && _publishedOnce && Time.unscaledTime < _nextSweep) return;
            s_dirty = false;
            _nextSweep = Time.unscaledTime + SweepSeconds;

            if (!_roomsResolved && !TryResolveRooms()) return;
            if (!TryGetPlayer(out var p))
            {
                // 접속이 끊겼다. 다음 스폰 때 현재 값을 다시 1회 보내야 한다 — 안 그러면 재접속한 FE 가
                // 끊기기 전 값을 그대로 믿는다.
                _publishedOnce = false;
                return;
            }

            int inside = 0;
            foreach (var r in _rooms)
            {
                // XZ 만 본다 — 방 높이는 넉넉히 잡혀 있고, 점프 중에도 "안" 이어야 한다.
                if (p.x >= r.bounds.min.x && p.x <= r.bounds.max.x && p.z >= r.bounds.min.z && p.z <= r.bounds.max.z)
                { inside = r.boothId; break; }
            }

            if (_publishedOnce && inside == CurrentBoothId) return;
            CurrentBoothId = inside;
            _publishedOnce = true;
            Festa.Integration.BoothInteractBridge.SendBoothContext(inside > 0, inside);
        }

        /// <summary>
        /// <c>@BoothInteriors/Interior_NN</c> 방들의 경계를 한 번 잰다. 씬이 아직 안 섰으면 다음 프레임에 다시 본다.
        /// 경계는 렌더러(바닥·벽) 합집합에 여유 1 u 를 더한다 — 문턱에 서 있을 때 값이 떨리지 않게.
        /// </summary>
        bool TryResolveRooms()
        {
            var root = GameObject.Find("@BoothInteriors");
            if (root == null) return false;

            _rooms.Clear();
            foreach (Transform room in root.transform)
            {
                if (!room.name.StartsWith("Interior_") || !int.TryParse(room.name.Substring(9), out int id)) continue;
                var renderers = room.GetComponentsInChildren<Renderer>(true);
                if (renderers.Length == 0) continue;

                var b = renderers[0].bounds;
                foreach (var r in renderers) b.Encapsulate(r.bounds);
                b.Expand(new Vector3(2f, 0f, 2f));

                var rp = GameObject.Find($"ReturnPoint_{id:00}")?.transform;
                _rooms.Add((id, b, rp));
            }

            _roomsResolved = _rooms.Count > 0;
            if (!_roomsResolved && !_warnedNoRooms)
            {
                _warnedNoRooms = true;   // 스윕마다 같은 줄을 쌓지 않는다
                Debug.LogWarning("[BoothContextPresenter] @BoothInteriors 는 있는데 Interior_NN 방이 없다 — 부스 컨텍스트를 보내지 못한다.");
            }
            return _roomsResolved;
        }

        static bool TryGetPlayer(out Vector3 p)
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var obj = (nm != null && nm.IsClient) ? nm.LocalClient?.PlayerObject : null;
            if (obj != null) { p = obj.transform.position; return true; }
            p = default;
            return false;
        }
    }
}
