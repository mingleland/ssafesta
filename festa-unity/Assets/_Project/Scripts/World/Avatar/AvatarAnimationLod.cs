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

        /// <summary>표시 상한 A/B 스위치. 끄면 모든 아바타를 그린다.</summary>
        public static bool RenderCapEnabled = true;

        [Tooltip("이 거리(월드 유닛) 안쪽은 매 프레임 갱신한다. 1 m = 13.26 유닛 → 220 u ≈ 16.6 m.")]
        [SerializeField] float _nearDistance = 220f;
        [Tooltip("이 거리 안쪽은 midInterval 프레임마다 갱신한다.")]
        [SerializeField] float _midDistance = 480f;
        [SerializeField] int _midInterval = 2;
        [SerializeField] int _farInterval = 4;

        /// <summary>
        /// 이 수 이하로는 간격 갱신을 **아예 걸지 않는다.**
        ///
        /// <para>이 장치의 근거는 위 주석 그대로 "40기를 화면에 담았을 때 애니메이터·본 갱신이 4.7 ms" 다.
        /// 그런데 밴드 판정이 거리뿐이라 <b>월드에 두세 명뿐일 때도 똑같이 걸린다.</b> 그 상황에서
        /// 아끼는 시간은 0.2 ms 남짓인데, 16.6 m 밖의 사람이 2프레임마다(60 Hz 화면에서 30 갱신/초)
        /// 움직여 눈에 띄게 끊긴다 — 사용자 보고 2026-09-13.</para>
        ///
        /// <para>비용이 사람 수에 비례하므로 게이트도 사람 수로 둔다. 넘는 순간부터 원래대로 동작한다.</para>
        /// </summary>
        [Tooltip("등록된 아바타가 이 수 이하면 간격 갱신을 걸지 않는다 — 적은 인원에서는 아끼는 값보다 끊김이 크다")]
        [SerializeField] int _minAvatarsToThrottle = 8;

        /// <summary>
        /// 화면에 동시에 그릴 아바타 수의 상한.
        ///
        /// <para>왜 필요한가 — WebGL 실측(2026-09-09)에서 병목은 픽셀이 아니라 <b>드로우콜 제출</b>이었다.
        /// 아바타 0기 219콜 101 FPS / 20기 719콜 49 FPS / 40기 990콜 25.7 FPS. 해상도를 1/9로 줄여도
        /// 40기에서 12%만 올랐다 — CPU 쪽이다. 아바타 1기가 20~25콜을 더한다.</para>
        ///
        /// <para>그래서 <b>먼 순서대로 그리기를 멈춘다.</b> 애니메이터 간격 갱신(위)은 평가 비용만 줄이고
        /// 드로우콜은 그대로 나가므로, 이 상한이 그 몫을 직접 없앤다.</para>
        ///
        /// <para><b>상한은 고정값이 아니라 프레임이 정한다.</b> 축제는 북적여야 제 맛이라, 기계가
        /// 버티는 동안에는 <b>한 명도 숨기지 않는다</b>. 고정 상한을 두면 잘 도는 PC 에서도 뒤쪽
        /// 사람들이 미리 지워져 인원이 실제보다 적어 보인다 — 얻는 것보다 잃는 것이 크다.
        /// 그래서 프레임 시간을 재서 <see cref="_targetFrameMs"/> 를 넘길 때만 먼 쪽부터 조금씩
        /// 덜어내고, 여유가 돌아오면 다시 채운다. 사양이 좋은 기기에서는 이 장치가 아무 일도 하지
        /// 않는다 (사용자 지시 2026-09-18).</para>
        ///
        /// <para>보이던 사람이 사라지는 것은 사용자가 가장 싫어하는 증상이다(2026-09-16 "투명으로 보임").
        /// 그래서 두 겹으로 막는다 — ① <see cref="_nearDistance"/> 안쪽은 <b>절대</b> 숨기지 않는다.
        /// ② 숨김/복귀 경계를 <see cref="_renderCapMargin"/> 만큼 벌려 경계에서 깜빡이지 않게 한다.
        /// 즉 "16.6 m 밖에 있으면서 동시에 N번째보다 먼 사람"만 대상이다. 닉네임·말풍선은 플레이어
        /// 루트에 붙어 있어 그대로 남는다 — 누가 거기 있다는 사실은 계속 보인다.</para>
        /// </summary>
        [Tooltip("프레임이 버거울 때 줄여 내려가는 하한 — 이 수보다 적게 그리지는 않는다")]
        [SerializeField] int _renderCapFloor = 16;
        [Tooltip("한 번에 그릴 수 있는 최대 — 이 값에 닿으면 아무도 숨기지 않는다")]
        [SerializeField] int _renderCapCeiling = 128;
        [Tooltip("이 프레임 시간(ms)을 넘기면 상한을 줄인다. 22 ms ≈ 45 FPS")]
        [SerializeField] float _targetFrameMs = 22f;
        [Tooltip("이 프레임 시간(ms) 아래로 내려오면 상한을 다시 늘린다. 16 ms ≈ 62 FPS")]
        [SerializeField] float _recoverFrameMs = 16f;
        [Tooltip("상한을 조정하는 간격(프레임). 너무 자주 바꾸면 인원이 출렁인다")]
        [SerializeField] int _capAdjustInterval = 45;
        [Tooltip("한 번에 늘리고 줄이는 폭(기수)")]
        [SerializeField] int _capAdjustStep = 2;
        [Tooltip("숨김과 복귀 경계를 벌리는 폭(기수) — 경계에서 깜빡이는 것을 막는다")]
        [SerializeField] int _renderCapMargin = 4;

        // 지금 적용 중인 상한. 처음에는 천장에서 시작한다 — 즉 기본 동작은 "전부 그린다" 다.
        int _renderCap = int.MaxValue;
        float _smoothedFrameMs;
        int _nextCapAdjustFrame;

        static AvatarAnimationLod _instance;

        struct Entry
        {
            public Animator Animator;
            public Renderer Probe;                 // 가시성 판정용 대표 렌더러
            public SkinnedMeshRenderer[] Skins;    // 본 가중치를 낮출 대상
            public Renderer[] Visuals;             // 표시 상한으로 끄고 켤 대상(아바타 하위 전부)
            public bool[] VisualWasEnabled;        // 끄기 직전 상태 — 병합이 끈 렌더러를 되살리지 않으려고 기억한다
            public bool Hidden;                    // 표시 상한으로 숨긴 상태인가
            public float Pending;                  // 아직 애니메이터에 넘기지 않은 시간
            public int Phase;                      // 프레임 분산용 위상
            public int Band;                       // 0 근 / 1 중 / 2 원 — 전환 시에만 품질을 바꾼다
        }

        readonly List<Entry> _entries = new();

        // 거리 순위를 매기는 데 쓰는 재사용 버퍼 — 매 프레임 할당하지 않으려고 필드로 둔다.
        float[] _sortBuffer = new float[64];

        /// <summary>
        /// 프레임 시간을 보고 상한을 올리거나 내린다.
        ///
        /// <para>한 프레임만 보고 움직이면 로딩·GC 처럼 일시적인 튐에 인원이 출렁인다. 지수이동평균으로
        /// 다듬고, 조정도 <see cref="_capAdjustInterval"/> 프레임에 한 번만 한다. 늘릴 때와 줄일 때의
        /// 문턱을 따로 둬(<see cref="_targetFrameMs"/> / <see cref="_recoverFrameMs"/>) 경계에서
        /// 왕복하지 않게 했다.</para>
        /// </summary>
        void UpdateAdaptiveCap(float deltaTime, int liveCount)
        {
            float frameMs = deltaTime * 1000f;
            _smoothedFrameMs = _smoothedFrameMs <= 0f ? frameMs : Mathf.Lerp(_smoothedFrameMs, frameMs, .05f);

            int ceiling = Mathf.Max(_renderCapFloor, _renderCapCeiling);
            if (_renderCap > ceiling) _renderCap = ceiling;

            if (Time.frameCount < _nextCapAdjustFrame) return;
            _nextCapAdjustFrame = Time.frameCount + Mathf.Max(5, _capAdjustInterval);

            int step = Mathf.Max(1, _capAdjustStep);
            if (_smoothedFrameMs > _targetFrameMs)
            {
                // 지금 그리고 있는 수보다 낮춰야 실제로 덜어진다 — 천장에 걸려 있으면 인원 수에서 시작한다.
                int current = Mathf.Min(_renderCap, Mathf.Max(liveCount, _renderCapFloor));
                _renderCap = Mathf.Max(_renderCapFloor, current - step);
            }
            else if (_smoothedFrameMs < _recoverFrameMs)
            {
                _renderCap = Mathf.Min(ceiling, _renderCap + step);
            }
        }

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
                Probe = PickLiveProbe(skins),
                Skins = skins,
                // 모자·소품처럼 스킨이 아닌 렌더러가 섞여 있다. 하나만 빼먹으면 "옷만 벗겨진" 모습이 되므로
                // 아바타 하위 렌더러를 종류 구분 없이 전부 잡는다. 닉네임·말풍선은 애니메이터보다 위(플레이어
                // 루트)에 있어 여기 들어오지 않는다.
                Visuals = animator.GetComponentsInChildren<Renderer>(true),
                VisualWasEnabled = null,
                Hidden = false,
                Pending = 0f,
                Phase = _instance._entries.Count,
                Band = -1,   // 첫 프레임에 반드시 한 번 적용되도록
            });
        }

        /// <summary>
        /// 가시성 대표 렌더러는 **켜져 있는** 것이어야 한다. 꺼진 렌더러의 <c>isVisible</c> 은 항상 false 라
        /// 중·원거리 밴드에서 애니메이터가 한 번도 돌지 않는다 — 헤어 01·04 착용자가 16.6 m 밖에서
        /// 자세가 얼어붙은 채 미끄러지던 원인(QA 2026-09-08 #57). 병합이 원본 신체 렌더러를 끄면서
        /// `skins[0]` 이 꺼진 렌더러가 되는 조합이 153개 중 4개 있었다.
        /// 켜진 것이 하나도 없으면 null → "항상 보인다" 로 취급해 안전한 쪽으로 기운다.
        /// </summary>
        static Renderer PickLiveProbe(SkinnedMeshRenderer[] skins)
        {
            for (var i = 0; i < skins.Length; i++)
                if (skins[i] != null && skins[i].enabled && skins[i].gameObject.activeInHierarchy) return skins[i];
            return null;
        }

        public static void Unregister(Animator animator)
        {
            if (_instance == null || animator == null) return;
            for (var i = 0; i < _instance._entries.Count; i++)
            {
                if (_instance._entries[i].Animator != animator) continue;
                // 원래 상태로 돌려놓고 뺀다 — 남겨두면 애니메이터가 멈춘 채로 방치된다.
                if (animator) animator.enabled = true;
                ShowVisuals(_instance._entries[i]);
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

            // ── 표시 상한: 이번 프레임의 숨김/복귀 경계 거리를 먼저 구한다 ──
            // 순위가 아니라 "경계 거리"로 바꿔 두면 아래 루프에서 비교 한 번으로 끝난다.
            float hideBeyondSqr = float.PositiveInfinity;   // 이보다 멀면 숨긴다
            float showWithinSqr = float.PositiveInfinity;   // 이보다 가까우면 되살린다
            UpdateAdaptiveCap(dt, _entries.Count);
            bool capActive = RenderCapEnabled && camera != null && _entries.Count > _renderCap;
            if (capActive)
            {
                if (_sortBuffer.Length < _entries.Count) _sortBuffer = new float[_entries.Count * 2];
                int n = 0;
                var camPos = camera.transform.position;
                for (var i = 0; i < _entries.Count; i++)
                {
                    var a = _entries[i].Animator;
                    if (a == null) continue;
                    _sortBuffer[n++] = (a.transform.position - camPos).sqrMagnitude;
                }
                if (n > _renderCap)
                {
                    System.Array.Sort(_sortBuffer, 0, n);
                    int showRank = Mathf.Clamp(_renderCap - 1, 0, n - 1);
                    int hideRank = Mathf.Min(n - 1, _renderCap + Mathf.Max(0, _renderCapMargin) - 1);
                    showWithinSqr = _sortBuffer[showRank];
                    hideBeyondSqr = _sortBuffer[hideRank];
                }
                else capActive = false;
            }
            float neverHideSqr = _nearDistance * _nearDistance;

            for (var i = _entries.Count - 1; i >= 0; i--)
            {
                var e = _entries[i];
                if (e.Animator == null) { _entries.RemoveAt(i); continue; }

                // 스위치가 꺼져 있거나, 카메라가 없거나, 사람이 적으면 Unity 기본 동작으로 되돌린다.
                if (!Enabled || camera == null || _entries.Count <= _minAvatarsToThrottle)
                {
                    if (!e.Animator.enabled) { e.Animator.enabled = true; e.Pending = 0f; }
                    if (e.Band != 0) { ApplySkinQuality(e, 0); e.Band = 0; }
                    if (e.Hidden && !capActive) ShowVisuals(ref e);
                    if (capActive) ApplyRenderCap(ref e, (e.Animator.transform.position - camera.transform.position).sqrMagnitude, neverHideSqr, hideBeyondSqr, showWithinSqr);
                    _entries[i] = e;
                    continue;
                }

                float sqrDistance = (e.Animator.transform.position - camera.transform.position).sqrMagnitude;

                if (capActive) ApplyRenderCap(ref e, sqrDistance, neverHideSqr, hideBeyondSqr, showWithinSqr);
                else if (e.Hidden) ShowVisuals(ref e);

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
                // 조립·병합이 렌더러를 껐다 켰다 하므로, 대표 렌더러가 꺼져 있으면 살아 있는 것으로 갈아탄다.
                if (e.Probe != null && (!e.Probe.enabled || !e.Probe.gameObject.activeInHierarchy))
                    e.Probe = PickLiveProbe(e.Skins);
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

        /// <summary>
        /// 거리 순위에 따라 아바타 렌더러를 끄고 켠다.
        ///
        /// <para>숨김 조건은 둘을 <b>모두</b> 만족할 때다 — ① 근거리 밖 ② 상한+여유 순위 밖.
        /// 복귀는 상한 순위 안으로 들어오거나 근거리 안으로 들어오면 즉시.</para>
        /// </summary>
        static void ApplyRenderCap(ref Entry e, float sqrDistance, float neverHideSqr, float hideBeyondSqr, float showWithinSqr)
        {
            if (e.Visuals == null || e.Visuals.Length == 0) return;

            if (!e.Hidden)
            {
                if (sqrDistance > neverHideSqr && sqrDistance > hideBeyondSqr) HideVisuals(ref e);
                return;
            }
            if (sqrDistance <= neverHideSqr || sqrDistance <= showWithinSqr) ShowVisuals(ref e);
        }

        /// <summary>
        /// 렌더러를 끄되 <b>끄기 직전에 켜져 있던 것만</b> 기억한다.
        /// 병합(<c>AvatarMeshMerge</c>)이 원본 렌더러를 꺼 둔 상태라, 되살릴 때 전부 켜면
        /// 같은 몸이 두 번 그려지고 옷이 겹쳐 보인다 — 반드시 원래 상태로만 되돌린다.
        /// </summary>
        static void HideVisuals(ref Entry e)
        {
            if (e.VisualWasEnabled == null || e.VisualWasEnabled.Length != e.Visuals.Length)
                e.VisualWasEnabled = new bool[e.Visuals.Length];
            for (var i = 0; i < e.Visuals.Length; i++)
            {
                var r = e.Visuals[i];
                if (r == null) { e.VisualWasEnabled[i] = false; continue; }
                e.VisualWasEnabled[i] = r.enabled;
                if (r.enabled) r.enabled = false;
            }
            e.Hidden = true;
        }

        static void ShowVisuals(ref Entry e)
        {
            if (e.Visuals != null && e.VisualWasEnabled != null)
            {
                for (var i = 0; i < e.Visuals.Length && i < e.VisualWasEnabled.Length; i++)
                {
                    var r = e.Visuals[i];
                    if (r != null && e.VisualWasEnabled[i]) r.enabled = true;
                }
            }
            e.Hidden = false;
        }

        /// <summary>목록에서 뺄 때처럼 참조로 되돌릴 수 없는 자리에서 쓰는 형태.</summary>
        static void ShowVisuals(Entry e) => ShowVisuals(ref e);

        void OnDestroy()
        {
            foreach (var e in _entries)
            {
                if (e.Animator) e.Animator.enabled = true;
                ApplySkinQuality(e, 0);
                ShowVisuals(e);
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
            int hidden = 0;
            foreach (var e in _instance._entries) if (e.Hidden) hidden++;
            string cap = !RenderCapEnabled ? "끔"
                       : _instance._renderCap >= _instance._renderCapCeiling ? "제한 없음"
                       : _instance._renderCap.ToString();
            return $"등록 {_instance._entries.Count}기 — 근 {near} / 중 {mid} / 원 {far}, "
                 + $"표시상한 {cap} 숨김 {hidden}기 ({_instance._smoothedFrameMs:F1} ms), 스위치={Enabled}";
        }
    }
}
