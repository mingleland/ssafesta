using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 거리에 따라 아바타 애니메이터의 **갱신 빈도**를 낮춘다.
    ///
    /// 왜 이걸 하나 — 40기를 화면에 담고 컴포넌트를 하나씩 꺼서 비용을 분해했다
    /// (2026-08-25, 에디터·TwoBones·40기가 더하는 13.2 ms):
    ///   · 스키닝                       ~5.8 ms (44%)
    ///   · 애니메이터 평가 + 본 Transform ~4.7 ms (36%)  ← 이 컴포넌트가 노리는 몫
    ///   · 드로우콜 제출                 ~2.7 ms (20%)
    /// 드로우콜이 20%뿐이라 아틀라스는 접었다. 남은 큰 두 몫 중 애니메이터·본 갱신은
    /// **스크립트만으로** 줄일 수 있다 — 에셋·프리팹·셰이더를 건드리지 않으므로 빌드 위험이 없다.
    ///
    /// 방법 — 멀리 있는 아바타는 매 프레임 평가하지 않는다. `Animator.enabled = false` 로 두고
    /// N 프레임마다 <see cref="Animator.Update"/> 를 **그동안 쌓인 시간만큼** 직접 돌린다.
    /// 재생 속도는 유지되고 평가 횟수만 줄어든다.
    ///
    /// ⚠ 주의 둘.
    ///   ① `enabled = false` 로 두면 <see cref="AnimatorCullingMode"/> 가 동작하지 않는다.
    ///      화면 밖을 걸러 주던 `CullUpdateTransforms` 가 사라지므로 **가시성을 직접 확인**한다.
    ///   ② 모든 원거리 아바타가 같은 프레임에 몰려 갱신되면 그 프레임만 튄다. 등록 순서로
    ///      위상을 나눠 프레임에 분산한다.
    ///
    /// 씬에 미리 놓을 필요가 없다 — 첫 등록 때 런타임 오브젝트를 만든다.
    /// </summary>
    [DefaultExecutionOrder(-50)]   // 애니메이터를 돌린 뒤에 AvatarLook(LateUpdate)이 본을 만진다
    public sealed class AvatarAnimationLod : MonoBehaviour
    {
        /// <summary>측정·비교용 전역 스위치. 끄면 모든 아바타가 매 프레임 평가된다.</summary>
        public static bool Enabled = true;

        [Tooltip("이 거리(월드 유닛) 안쪽은 매 프레임 갱신한다. 1 m = 10 유닛.")]
        [SerializeField] float _nearDistance = 220f;
        [Tooltip("이 거리 안쪽은 midInterval 프레임마다 갱신한다.")]
        [SerializeField] float _midDistance = 480f;
        [SerializeField] int _midInterval = 2;
        [SerializeField] int _farInterval = 4;

        static AvatarAnimationLod _instance;

        struct Entry
        {
            public Animator Animator;
            public Renderer Probe;                 // 가시성 판정용 대표 렌더러
            public SkinnedMeshRenderer[] Skins;    // 본 가중치를 낮출 대상
            public float Pending;                  // 아직 애니메이터에 넘기지 않은 시간
            public int Phase;                      // 프레임 분산용 위상
            public int Band;                       // 0 근 / 1 중 / 2 원 — 전환 시에만 품질을 바꾼다
        }

        readonly List<Entry> _entries = new();

        public static void Register(Animator animator)
        {
            if (animator == null) return;
            EnsureInstance();
            for (var i = 0; i < _instance._entries.Count; i++)
                if (_instance._entries[i].Animator == animator) return;

            var skins = animator.GetComponentsInChildren<SkinnedMeshRenderer>(true);
            _instance._entries.Add(new Entry
            {
                Animator = animator,
                Probe = skins.Length > 0 ? skins[0] : null,
                Skins = skins,
                Pending = 0f,
                Phase = _instance._entries.Count,
                Band = -1,   // 첫 프레임에 반드시 한 번 적용되도록
            });
        }

        public static void Unregister(Animator animator)
        {
            if (_instance == null || animator == null) return;
            for (var i = 0; i < _instance._entries.Count; i++)
            {
                if (_instance._entries[i].Animator != animator) continue;
                // 원래 상태로 돌려놓고 뺀다 — 남겨두면 애니메이터가 멈춘 채로 방치된다.
                if (animator) animator.enabled = true;
                _instance._entries.RemoveAt(i);
                return;
            }
        }

        static void EnsureInstance()
        {
            if (_instance != null) return;
            var go = new GameObject("@AvatarAnimationLod") { hideFlags = HideFlags.DontSave };
            _instance = go.AddComponent<AvatarAnimationLod>();
        }

        void Update()
        {
            var camera = Camera.main;
            float dt = Time.deltaTime;

            for (var i = _entries.Count - 1; i >= 0; i--)
            {
                var e = _entries[i];
                if (e.Animator == null) { _entries.RemoveAt(i); continue; }

                // 스위치가 꺼져 있거나 카메라가 없으면 Unity 기본 동작으로 되돌린다.
                if (!Enabled || camera == null)
                {
                    if (!e.Animator.enabled) { e.Animator.enabled = true; e.Pending = 0f; }
                    if (e.Band != 0) { ApplySkinQuality(e, 0); e.Band = 0; }
                    _entries[i] = e;
                    continue;
                }

                float sqrDistance = (e.Animator.transform.position - camera.transform.position).sqrMagnitude;

                int band = sqrDistance <= _nearDistance * _nearDistance ? 0
                         : sqrDistance <= _midDistance * _midDistance ? 1 : 2;
                if (band != e.Band) { ApplySkinQuality(e, band); e.Band = band; }

                if (band == 0)
                {
                    // 근거리는 Unity 에 맡긴다 — cullingMode 가 화면 밖을 알아서 걸러 준다.
                    if (!e.Animator.enabled) { e.Animator.enabled = true; e.Pending = 0f; }
                    _entries[i] = e;
                    continue;
                }

                int interval = band == 1 ? _midInterval : _farInterval;
                if (interval < 2) interval = 2;

                if (e.Animator.enabled) e.Animator.enabled = false;
                e.Pending += dt;

                // 화면 밖이면 쌓기만 하고 평가하지 않는다 — cullingMode 가 하던 일을 대신한다.
                // 다시 보이면 그동안 쌓인 시간을 한 번에 넘겨 자세가 이어진다.
                bool visible = e.Probe == null || e.Probe.isVisible;
                if (visible && (Time.frameCount + e.Phase) % interval == 0)
                {
                    e.Animator.Update(e.Pending);
                    e.Pending = 0f;
                }

                _entries[i] = e;
            }
        }

        /// <summary>
        /// 거리에 따라 **본 가중치**를 낮춘다. 스키닝 비용은 정점 수 x 정점당 본 수로 결정되므로,
        /// 정점을 줄이는 LOD 메시를 만들지 않고도 절반으로 내릴 수 있다 — 에셋 작업이 0이다.
        ///
        /// 근거리 Auto(품질 설정을 따름) / 중거리 2본 / 원거리 1본. 1본은 관절이 접히는 곳에서
        /// 뾰족해지지만 그 거리에서는 몇 픽셀이라 보이지 않는다.
        /// 밴드가 **바뀔 때만** 호출한다 — 매 프레임 대입하면 그 자체가 비용이다.
        /// </summary>
        static void ApplySkinQuality(Entry e, int band)
        {
            if (e.Skins == null) return;
            var q = band == 0 ? SkinQuality.Auto : band == 1 ? SkinQuality.Bone2 : SkinQuality.Bone1;
            for (var i = 0; i < e.Skins.Length; i++)
                if (e.Skins[i]) e.Skins[i].quality = q;
        }

        void OnDestroy()
        {
            foreach (var e in _entries)
            {
                if (e.Animator) e.Animator.enabled = true;
                ApplySkinQuality(e, 0);
            }
            if (_instance == this) _instance = null;
        }

        /// <summary>측정용 — 현재 등록된 아바타 수와 구간별 분포.</summary>
        public static string DescribeState()
        {
            if (_instance == null) return "등록 없음";
            var camera = Camera.main;
            int near = 0, mid = 0, far = 0;
            foreach (var e in _instance._entries)
            {
                if (e.Animator == null || camera == null) continue;
                float d = Vector3.Distance(e.Animator.transform.position, camera.transform.position);
                if (d <= _instance._nearDistance) near++;
                else if (d <= _instance._midDistance) mid++;
                else far++;
            }
            return $"등록 {_instance._entries.Count}기 — 근 {near} / 중 {mid} / 원 {far}, 스위치={Enabled}";
        }
    }
}
