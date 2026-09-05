using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// WebGL(GLES3/UBO)은 한 프레임에 존재할 수 있는 추가 광원이 전 화면 32개다
    /// (URP MAX_VISIBLE_LIGHTS_UBO — 에디터 D3D는 256이라 에디터에서는 재현되지 않는다).
    /// 이 씬의 실시간 광원은 142개라, 절두체에 32개 초과가 들어오면 어느 32개가
    /// 살아남을지 카메라 이동마다 재배정된다 — T-216 "거리 따라 조명 깜빡임"의 원인.
    ///
    /// 해결: 카메라에서 가까운 순서로 예산(_fullBudget)까지만 완전 점등하고,
    /// 그 뒤 순위(_fadeBudget까지)는 세기를 순위에 비례해 줄이며, 나머지는 끈다.
    /// 목표 세기는 매 프레임 보간(_fadeSpeed)으로 따라가므로 순위가 뒤바뀌어도
    /// 화면에서는 크로스페이드로만 보인다. 항상 32개 미만이 보장된다.
    ///
    /// 라이트맵 베이크가 정석이지만 이 월드(스케치업 지오메트리)는 UV 정리가
    /// 선행돼야 해서 별건으로 미뤘다 — 그때까지의 안정화 장치다.
    /// </summary>
    public class WorldLightBudget : MonoBehaviour
    {
        [SerializeField] int _fullBudget = 24;    // 이 순위까지는 원래 세기
        [SerializeField] int _fadeBudget = 30;    // 이 순위까지 세기 페이드, 그 뒤는 소등 (32 미만!)
        [SerializeField] float _sortInterval = 0.25f;
        [SerializeField] float _fadeSpeed = 2.5f; // 초당 멀티플라이어 변화량

        public static bool Enabled = true;

        class Entry
        {
            public Light Light;
            public float BaseIntensity;
            public float Current;   // 현재 멀티플라이어
            public float Target;    // 목표 멀티플라이어
            public float SortKey;   // 이번 재정렬의 우선순위 키 (절두체 안: 거리², 밖: 거리² + 큰 벌점)
        }

        readonly List<Entry> _entries = new();
        float _nextSort;
        bool _suspended;

        void Start()
        {
            foreach (var l in FindObjectsByType<Light>(FindObjectsSortMode.None))
            {
                if (l.type == LightType.Directional) continue;
                _entries.Add(new Entry { Light = l, BaseIntensity = l.intensity, Current = 1f, Target = 1f });
            }
            // 예산이 캡을 넘으면 이 장치는 거짓말이 된다 — 소리 내고 죽는다.
            if (_fadeBudget >= 32)
            {
                Debug.LogError($"[WorldLightBudget] fadeBudget {_fadeBudget} ≥ 32 — WebGL 캡을 넘는 설정, 비활성화");
                enabled = false;
            }
        }

        void Update()
        {
            if (!Enabled)
            {
                if (!_suspended) { RestoreAll(); _suspended = true; }
                return;
            }
            _suspended = false;

            if (Time.unscaledTime >= _nextSort)
            {
                _nextSort = Time.unscaledTime + _sortInterval;
                Retarget();
            }

            float step = _fadeSpeed * Time.deltaTime;
            foreach (var e in _entries)
            {
                if (e.Light == null) continue;
                if (Mathf.Approximately(e.Current, e.Target) && e.Light.enabled == e.Current > 0.005f) continue;
                e.Current = Mathf.MoveTowards(e.Current, e.Target, step);
                bool on = e.Current > 0.005f;
                if (e.Light.enabled != on) e.Light.enabled = on;
                if (on) e.Light.intensity = e.BaseIntensity * e.Current;
            }
        }

        void Retarget()
        {
            var cam = Camera.main;
            if (cam == null) return;
            var origin = cam.transform.position;

            _entries.RemoveAll(e => e.Light == null);

            // **보이는 광원부터 예산을 쓴다.** 순수 거리순으로 자르면 카메라 뒤·옆의 광원이 예산을 먹어
            // 정면 벽의 조명이 꺼진 채로 보이고, 걸어가야 켜진다 — "한 화면 안인데 저쪽만 어둡다"
            // (2026-09-05 사용자 테스트). WebGL 32개 캡은 **절두체 안 광원** 수에 걸리므로 절두체 밖은
            // 애초에 캡을 소모하지 않는다. 그래서 절두체 안을 먼저(거리순), 밖은 뒤로 보낸다.
            // 판정은 광원 범위 구를 감싸는 AABB 로 한다 — 광원 중심이 화면 밖이어도 빛이 닿는 면은 보일 수 있다.
            var planes = GeometryUtility.CalculateFrustumPlanes(cam);
            const float OutsidePenalty = 1e9f;
            foreach (var e in _entries)
            {
                var t = e.Light.transform;
                float r = e.Light.range;
                bool inView = GeometryUtility.TestPlanesAABB(planes, new Bounds(t.position, Vector3.one * (r * 2f)));
                e.SortKey = (t.position - origin).sqrMagnitude + (inView ? 0f : OutsidePenalty);
            }
            _entries.Sort((a, b) => a.SortKey.CompareTo(b.SortKey));

            for (int i = 0; i < _entries.Count; i++)
            {
                if (i < _fullBudget) _entries[i].Target = 1f;
                else if (i < _fadeBudget)
                    _entries[i].Target = 1f - (i - _fullBudget + 1) / (float)(_fadeBudget - _fullBudget + 1);
                else _entries[i].Target = 0f;
            }
        }

        void RestoreAll()
        {
            foreach (var e in _entries)
            {
                if (e.Light == null) continue;
                e.Current = e.Target = 1f;
                e.Light.enabled = true;
                e.Light.intensity = e.BaseIntensity;
            }
        }

        void OnDestroy()
        {
            RestoreAll();
        }
    }
}
