using System.Collections.Generic;
using UnityEngine;

namespace Festa.World.Festival
{
    /// <summary>
    /// 부스 내부 12실을 **플레이어가 가까이 있을 때만 그린다** (S15P21A604-508).
    ///
    /// 왜 필요한가: 부스 내부는 본 맵 바깥 X +630~+2870 에 일렬로 놓인 무대 뒤 공간인데,
    /// 카메라 far clip 이 5,200m 라 **축제장에서 동쪽을 보면 그 방들이 전부 시야에 들어온다.**
    /// 실측: 축제 서쪽 끝(-754, 173)에서 동쪽(yaw 85)을 보면 절두체 안 렌더러가 936개이고
    /// 그중 372개가 이 부스 내부다. 실측 드로우콜 1,120 과 일치했다.
    ///
    /// 사용자 표현으로는 "축제벽 끝쪽에 가면 조금씩 끊기고, 거길 벗어나도 마찬가지" 였다.
    /// 벗어나도 그대로였던 이유는 계속 동쪽을 보고 있어서였다 — 방향이 바뀌지 않으면
    /// 시야 내용도 그대로다. 서쪽(yaw 271)을 보면 같은 자리에서 239개로 떨어졌다.
    ///
    /// far clip 을 줄여서 해결할 수 없다: 배경 관람차가 X −2280 에 있어 축제장에서 1.5km 떨어져
    /// 있고, 부스 내부는 1.4~3.6km 구간에 걸쳐 그 거리와 겹친다. 거리로는 못 가른다.
    ///
    /// 오클루전 컬링도 답이 아니다 — 개활지라 가릴 것이 없어 이미 시도했다가 컬링 비용만 늘고
    /// 체감이 더 나빠져 되돌렸다(T-130).
    ///
    /// 그래서 **소속으로 가른다**: 부스 내부는 그 안에 있을 때만 보이면 된다.
    ///
    /// GameObject 를 끄지 않고 <see cref="Renderer"/> 와 <see cref="Light"/> 만 끈다.
    /// 각 방에 BoothRuntime(동결 기준선)과 BoothPortal 이 붙어 있어 오브젝트를 끄면 그 로직까지
    /// 멈춘다. 렌더러만 끄면 스크립트는 그대로 돌고 그리는 비용만 사라진다.
    /// 물리도 그대로다 — 콜라이더를 건드리지 않으므로 레이캐스트·상호작용은 영향이 없다.
    /// </summary>
    [DisallowMultipleComponent]
    public class BoothInteriorCulling : MonoBehaviour
    {
        [Tooltip("이 거리 안에 있을 때만 그 부스 내부를 그린다(m). 방 간격이 700m 라 넉넉히 잡아도 안전하다.")]
        [SerializeField] float _showDistance = 250f;
        // 매 프레임 판정한다. 방이 12개뿐이라 거리 계산 비용은 없고, 주기를 두면 텔레포트로 부스에
        // 들어간 직후 몇 프레임 동안 방이 비어 보인다 — 그 깜빡임이 절약보다 비싸다.
        // 실제 쓰기는 상태가 바뀔 때만 하므로 정지 상태에서는 아무 일도 하지 않는다.
        [Tooltip("거리 판정 주기(초). 0 이면 매 프레임(권장).")]
        [SerializeField] float _checkInterval = 0f;

        sealed class Room
        {
            public Transform Root;
            public Renderer[] Renderers;
            public Light[] Lights;
            public bool Visible;      // 지금 켜져 있나 — 상태가 바뀔 때만 쓴다
        }

        readonly List<Room> _rooms = new List<Room>();
        float _nextCheck;

        /// <summary>
        /// 방 목록을 다시 훑어야 하는가.
        ///
        /// <para><b>왜 필요한가.</b> 예전에는 <see cref="Awake"/> 에서 렌더러를 한 번만 스냅샷했다.
        /// 그런데 부스 집기·간판·소품은 그 뒤에 <c>WorldBoothPublishedBootstrap</c> 이 비동기로 스폰한다 —
        /// 즉 <b>정작 무거운 것들이 목록에 없어서 한 번도 꺼지지 않았다.</b> 꺼지던 것은 빈 방의
        /// 벽·바닥·천장뿐이라, "부스 내부를 껐는데도 축제장 동향에서 여전히 끊긴다" 가 됐다
        /// (2026-09-08 조사).</para>
        /// </summary>
        static bool s_rescanRequested;

        /// <summary>부스 내용물이 새로 스폰된 뒤 부른다. 다음 판정에서 목록을 다시 만든다.</summary>
        public static void RequestRescan() => s_rescanRequested = true;

        /// <summary>안전망. 명시적 요청을 놓쳐도 이 주기로 한 번은 따라잡는다.</summary>
        const float SafetyRescanSeconds = 5f;
        float _nextSafetyRescan;

        void Awake()
        {
            Collect();
            Festa.Booth.WorldBoothPublishedBootstrap.BoothsRebuilt += RequestRescan;
        }

        void OnDestroy() => Festa.Booth.WorldBoothPublishedBootstrap.BoothsRebuilt -= RequestRescan;

        void Collect()
        {
            _rooms.Clear();
            for (int i = 0; i < transform.childCount; i++)
            {
                var c = transform.GetChild(i);
                _rooms.Add(new Room
                {
                    Root = c,
                    Renderers = c.GetComponentsInChildren<Renderer>(true),
                    Lights = c.GetComponentsInChildren<Light>(true),
                    Visible = true,   // 다시 훑은 직후에는 상태를 모른다 — force 로 한 번 확정한다
                });
            }
        }

        void Start()
        {
            // 시작 시점에는 플레이어가 본 맵에 있으므로 전부 꺼진 상태로 출발한다.
            // Awake 에서 끄지 않는 이유: 다른 컴포넌트가 Awake 순서에 따라 렌더러를 만질 수 있다.
            Apply(force: true);
        }

        void Update()
        {
            // 부스 집기가 새로 스폰됐으면 목록부터 다시 만든다 — 안 그러면 그것들은 영영 안 꺼진다.
            if (s_rescanRequested || Time.unscaledTime >= _nextSafetyRescan)
            {
                bool explicitAsk = s_rescanRequested;
                s_rescanRequested = false;
                _nextSafetyRescan = Time.unscaledTime + SafetyRescanSeconds;

                int before = 0;
                foreach (var r in _rooms) before += r.Renderers.Length;
                Collect();
                int after = 0;
                foreach (var r in _rooms) after += r.Renderers.Length;

                if (after != before)
                    Debug.Log($"[BoothInteriorCulling] 방 목록 갱신 — 렌더러 {before} → {after}" +
                              (explicitAsk ? " (부스 스폰 알림)" : " (주기 확인)"));
                Apply(force: true);
                return;
            }

            if (_checkInterval > 0f)
            {
                if (Time.unscaledTime < _nextCheck) return;
                _nextCheck = Time.unscaledTime + _checkInterval;
            }
            Apply(force: false);
        }

        void Apply(bool force)
        {
            if (!TryGetViewer(out var viewer)) return;

            float sqr = _showDistance * _showDistance;
            for (int i = 0; i < _rooms.Count; i++)
            {
                var room = _rooms[i];
                if (room.Root == null) continue;

                bool near = (room.Root.position - viewer).sqrMagnitude <= sqr;
                if (!force && near == room.Visible) continue;   // 상태가 그대로면 아무것도 쓰지 않는다

                room.Visible = near;
                for (int r = 0; r < room.Renderers.Length; r++)
                    if (room.Renderers[r] != null) room.Renderers[r].enabled = near;
                for (int l = 0; l < room.Lights.Length; l++)
                    if (room.Lights[l] != null) room.Lights[l].enabled = near;
            }
        }

        /// <summary>
        /// 기준점은 **카메라**다. 플레이어가 아니라 카메라가 보는 것이 그려지기 때문이다.
        /// 접속 전이거나 카메라가 없으면 판정을 미룬다 — 잘못 끄느니 그대로 두는 편이 안전하다.
        /// </summary>
        static bool TryGetViewer(out Vector3 pos)
        {
            var cam = Camera.main;
            if (cam != null) { pos = cam.transform.position; return true; }
            pos = default;
            return false;
        }
    }
}
