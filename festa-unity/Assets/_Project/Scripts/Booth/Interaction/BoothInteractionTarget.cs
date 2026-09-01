using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>부스 프리팹의 클릭 범위와 비파괴 하이라이트를 통일한다.</summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteractionTarget : MonoBehaviour
    {
        static readonly int EmissionColor = Shader.PropertyToID("_EmissionColor");

        /// <summary>판정 거리. **월드 유닛**이며 콜라이더 표면 기준이다 (20f ≈ 1.5 m).</summary>
        [SerializeField, Min(0.5f)] float _maxDistance = 20f;
        [SerializeField] bool _highlightEnabled = true;
        [SerializeField] Color _highlightColor = new(0.25f, 0.7f, 1f, 1f);

        readonly List<Renderer> _renderers = new();
        MaterialPropertyBlock _block;

        // ── 근접 자동 조준용 레지스트리 ─────────────────────────
        // 디스패처가 매 프레임 "사거리 안의 가장 가까운 대상" 을 찾는다 (S15P21A604-346).
        // FindObjectsByType 을 매 프레임 돌리면 씬 전체를 훑으므로, 활성 대상이
        // 스스로 등록·해제한다. 월드 전체 대상은 수십 개 규모라 선형 탐색로 충분하다.
        public static readonly List<BoothInteractionTarget> Active = new();

        void OnEnable() => Active.Add(this);
        void OnDisable() => Active.Remove(this);

        public float MaxDistance => _maxDistance;

        /// <summary>
        /// 플레이어 위치에서 이 대상까지의 거리 — **콜라이더 표면 기준**이다.
        ///
        /// <para>전에는 <c>transform.position</c>(피벗)까지 쟀다. 그러면 큰 오브젝트일수록
        /// 표면에 몸이 닿아도 피벗은 멀어서, 사거리를 오브젝트 크기에 맞춰 크게 잡아야 했다
        /// (노트북은 피벗이 테이블 높이에 있어 표면에서 6.8 unit 이었다 — T-232).
        /// 사거리가 커지면 이번엔 **멀리서도 잡히는** 반대 문제가 생긴다.</para>
        ///
        /// <para>표면 기준으로 재면 사거리 값이 오브젝트 크기와 무관해져, "거의 붙어야 잡힌다"
        /// 를 크기가 제각각인 부스 오브젝트 전부에 한 숫자로 걸 수 있다 (S15P21A604-355).
        /// 포털(<see cref="Festa.World.BoothPortal"/>)이 쓰던 방식과 같다.</para>
        /// </summary>
        public float DistanceFrom(Vector3 pos)
        {
            var b = WorldBounds();
            return b.HasValue ? Vector3.Distance(pos, b.Value.ClosestPoint(pos))
                              : Vector3.Distance(pos, transform.position);
        }

        /// <summary>하이라이트 링을 놓을 바닥 지점과 반경 — 포털과 같은 표현을 쓴다.</summary>
        public (Vector3 pos, float radius) HighlightFootprint()
        {
            var b = WorldBounds();
            if (!b.HasValue)
                return (new Vector3(transform.position.x, transform.position.y + 0.6f, transform.position.z), 6f);
            var v = b.Value;
            return (new Vector3(v.center.x, v.min.y + 0.6f, v.center.z),
                    Mathf.Max(v.extents.x, v.extents.z) * 1.25f);
        }

        /// <summary>콜라이더 우선, 없으면 렌더러로 만든 월드 바운즈.</summary>
        Bounds? WorldBounds()
        {
            Bounds? acc = null;
            foreach (var c in GetComponentsInChildren<Collider>(true))
            {
                if (c == null || c.isTrigger) continue;
                if (acc == null) acc = c.bounds; else { var v = acc.Value; v.Encapsulate(c.bounds); acc = v; }
            }
            if (acc != null) return acc;
            foreach (var r in GetComponentsInChildren<Renderer>(true))
            {
                if (r == null) continue;
                if (acc == null) acc = r.bounds; else { var v = acc.Value; v.Encapsulate(r.bounds); acc = v; }
            }
            return acc;
        }

        /// <summary>F 에 실제로 응답하는 대상인가 (IBoothInteractable 보유 — 팩토리가 판정해 넘긴다).</summary>
        public bool Interactive { get; private set; }

        public void Configure(float maxDistance, bool highlightEnabled)
        {
            _maxDistance = Mathf.Max(0.5f, maxDistance);
            _highlightEnabled = highlightEnabled;
            Interactive = highlightEnabled;
            EnsureCollider();
            CacheRenderers();
        }

        public bool CanInteract(Camera source = null)
        {
            source ??= Camera.main;
            return source != null && Vector3.Distance(source.transform.position, transform.position) <= _maxDistance;
        }

        void Awake()
        {
            EnsureCollider();
            CacheRenderers();

            // 호버 감지는 중앙 디스패처가 한다. `OnMouseEnter/Exit` 은 Unity 6 WebGL 에서
            // 발생하지 않는다 (T-166) — 배포 환경에서 하이라이트가 아예 동작하지 않았고,
            // 이 메서드들이 존재하는 것만으로 Unity 가 매 프레임 레거시 마우스 디스패처를
            // 돌려 "Screen position out of view frustum" 경고를 뿜었다 (T-176).
            Festa.Content.BoothInteractionInput.Ensure();
        }

        /// <summary>디스패처가 호버 상태를 알려준다.</summary>
        public void SetHighlight(bool highlighted) => SetHighlighted(highlighted);

        void CacheRenderers()
        {
            _renderers.Clear();
            _renderers.AddRange(GetComponentsInChildren<Renderer>(true));
            _block ??= new MaterialPropertyBlock();
        }

        void EnsureCollider()
        {
            if (GetComponentInChildren<Collider>(true) != null) return;

            var renderers = GetComponentsInChildren<Renderer>(true);
            if (renderers.Length == 0) return;

            var bounds = renderers[0].bounds;
            for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);

            var collider = gameObject.AddComponent<BoxCollider>();
            collider.center = transform.InverseTransformPoint(bounds.center);
            var localMin = transform.InverseTransformPoint(bounds.min);
            var localMax = transform.InverseTransformPoint(bounds.max);
            collider.size = new Vector3(
                Mathf.Abs(localMax.x - localMin.x),
                Mathf.Abs(localMax.y - localMin.y),
                Mathf.Abs(localMax.z - localMin.z));
        }

        void SetHighlighted(bool highlighted)
        {
            if (!_highlightEnabled) return;
            if (_renderers.Count == 0) CacheRenderers();

            foreach (var targetRenderer in _renderers)
            {
                if (targetRenderer == null) continue;
                targetRenderer.GetPropertyBlock(_block);
                _block.SetColor(EmissionColor, highlighted ? _highlightColor * 0.65f : Color.black);
                targetRenderer.SetPropertyBlock(_block);
            }
        }
    }
}
