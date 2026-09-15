using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>부스 프리팹의 클릭 범위와 비파괴 하이라이트를 통일한다.</summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteractionTarget : MonoBehaviour
    {
        static readonly int EmissionColor = Shader.PropertyToID("_EmissionColor");

        /// <summary>
        /// 판정 거리. **월드 유닛**이며 콜라이더 표면 기준이다 (1 m = 13.26u).
        ///
        /// <para>2026-09-10 사용자 요청으로 20f → 10f 로 줄였다 — "거의 외곽에 붙었을 때만".
        /// 표면 기준 **수평** 거리라 오브젝트 크기·높이와 무관하고, 플레이어 캡슐 반경(2.75u)을 빼면
        /// 몸과 표면 사이가 7u(0.55 m) 남는다. 이보다 줄이면 캡슐이 표면에 닿아도 F 가 안 먹는 자리가 생긴다.</para>
        /// </summary>
        [SerializeField, Min(0.5f)] float _maxDistance = 10f;
        [SerializeField] bool _highlightEnabled = true;
        [Tooltip("강조 색. 발밑 링(금색)과 같은 계열이라야 같은 기능으로 읽힌다.")]
        [SerializeField] Color _highlightColor = new(1f, 0.82f, 0.35f, 1f);

        [Tooltip("발광 세기 — 너무 높이면 재질 색이 날아가 형태를 알아볼 수 없다.")]
        [SerializeField, Range(0.1f, 3f)] float _highlightStrength = 0.9f;

        readonly List<Renderer> _renderers = new();
        bool _highlighted;

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
        /// <summary>
        /// 아바타 키만큼은 "같은 높이" 로 본다. 22.375u 에 여유를 붙인 값 — 발밑(루트) 기준이라
        /// 머리 위 조금까지가 손이 닿는 범위다.
        /// </summary>
        const float VerticalSlack = 26f;

        public float DistanceFrom(Vector3 pos)
        {
            var b = WorldBounds();
            if (!b.HasValue) return Vector3.Distance(pos, transform.position);

            var closest = b.Value.ClosestPoint(pos);

            // **수평 거리로 잰다.** 3차원 거리로 재면 책상 위 노트북처럼 대상이 눈높이에 있을 때
            // 발밑 기준 높이 차만으로 이미 10u 라, 사거리를 "붙어야 잡힌다"(8u)로 줄이는 순간
            // 책상에 몸이 닿는 자리에서도 사거리 밖이 된다 (2026-09-10 실측 10.1u).
            float flat = new Vector2(pos.x - closest.x, pos.z - closest.z).magnitude;

            // 위아래로 멀리 떨어진 것(윗층·천장 부착물)까지 잡히면 안 되므로 여유를 넘는 높이 차는 더한다.
            float dy = Mathf.Abs(pos.y - closest.y);
            return flat + Mathf.Max(0f, dy - VerticalSlack);
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

        // ── 월드 바운즈 캐시 (S15P21A604-508) ────────────────────
        //
        // **매 프레임 계산하면 안 되는 값이다.** BoothInteractionInput.Update() 가 프레임마다
        // Active 전수를 돌며 DistanceFrom() 을 부르고, 사거리 안에 대상이 있으면 HighlightFootprint()
        // 로 한 번 더 부른다. 즉 프레임당 (활성 대상 수 + 최대 3)회 이 함수가 돌았다.
        //
        // 그 안의 GetComponentsInChildren<T>(true) 는 **호출마다 배열을 새로 할당**하고 계층을
        // 통째로 순회한다. 콜라이더가 없으면 렌더러로 한 번 더 돈다. 초당 100프레임 × 수십 대상이면
        // 초당 수천 번의 할당이고, WebGL/IL2CPP 에서 이건 그대로 GC 압력이 된다.
        //
        // 이 비용은 da3f05ec(2026-09-01, S15P21A604-355)에서 들어왔다. 그 전에는
        // `(t.transform.position - origin).sqrMagnitude` 한 줄이었다. 표면 거리로 바꾼 것 자체는
        // 옳다(피벗 거리는 큰 오브젝트에서 어긋난다 — T-232). **매 프레임 다시 계산한 것이 문제다.**
        //
        // 부스 오브젝트는 배치된 뒤 움직이지 않으므로 결과를 들고 있으면 된다. 다만 "안 움직인다"를
        // 전제로만 두지 않고 **트랜스폼이 바뀌면 스스로 무효화**한다 — 레이아웃 재적용이나 씬 편집으로
        // 옮겨져도 값이 낡지 않는다.
        Bounds? _cachedBounds;
        bool _boundsValid;
        Matrix4x4 _boundsMatrix;

        /// <summary>콜라이더 우선, 없으면 렌더러로 만든 월드 바운즈. 트랜스폼이 그대로면 캐시를 쓴다.</summary>
        Bounds? WorldBounds()
        {
            var m = transform.localToWorldMatrix;
            if (_boundsValid && m == _boundsMatrix) return _cachedBounds;

            _cachedBounds = ComputeWorldBounds();
            _boundsMatrix = m;
            _boundsValid = true;
            return _cachedBounds;
        }

        /// <summary>레이아웃 재적용처럼 **자식 구성이 바뀐** 경우 호출한다 — 트랜스폼 비교로는 못 잡는다.</summary>
        public void InvalidateBounds() => _boundsValid = false;

        Bounds? ComputeWorldBounds()
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
            // EnsureCollider 가 콜라이더를 새로 붙일 수 있다 — 바운즈가 달라지므로 캐시를 버린다.
            InvalidateBounds();
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

            // **씬에 직접 놓인 대상은 여기서 Interactive 를 켠다.** 전에는 팩토리의 Configure() 만 켰기
            // 때문에 관리 데스크(-414)·Festival_Arcade 처럼 씬에 배치된 대상은 런타임에 Interactive=false 로
            // 남아 디스패처가 조준·근접 대상에서 제외했다 — F 를 눌러도 아무 일이 없었다(T-123).
            // 판정 기준은 디스패처가 Interact 대상을 고르는 것과 같다: IBoothInteractable 이 자기/부모/자식에 있는가.
            // 팩토리는 이 뒤에 Configure() 로 덮어쓰므로 부스 오브젝트 동작은 바뀌지 않는다.
            if (!Interactive &&
                (GetComponentInParent<Festa.Content.IBoothInteractable>() != null ||
                 GetComponentInChildren<Festa.Content.IBoothInteractable>(true) != null))
            {
                Interactive = true;
                _highlightEnabled = true;
            }

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
            if (highlighted == _highlighted) return;
            _highlighted = highlighted;

            if (highlighted) ApplyHighlightMaterials();
            else RestoreMaterials();
        }

        // ── 외곽선 하이라이트 (S15P21A604-437) ──────────────────────
        // 그리는 방법은 Festa.World.OutlineHighlighter 한 곳에만 있다 — 부스 구조물(PortalInteractor)과
        // 같은 표현이라야 "상호작용할 수 있다" 가 한 가지 뜻으로 읽힌다 (2026-09-10 공용화).
        [Tooltip("이 트랜스폼 아래 렌더러에만 외곽선을 건다. 비우면 대상 전체 — 관리 데스크처럼 NPC+테이블이 " +
                 "한 대상이면 NPC 쪽 트랜스폼을 지정한다.")]
        [SerializeField] Transform _highlightRoot;
        [SerializeField, Range(0.05f, 2f)] float _outlineWidth = 0.35f;   // 월드 유닛 (1 m = 13.26)
        readonly Festa.World.OutlineHighlighter _outline = new();

        /// <summary>외곽선을 걸 트랜스폼을 코드에서 정한다(팩토리·씬 배치 양쪽).</summary>
        public void SetHighlightRoot(Transform root) => _highlightRoot = root;

        void ApplyHighlightMaterials()
            => _outline.Show(_highlightRoot != null ? _highlightRoot : transform, _highlightColor, _outlineWidth);

        void RestoreMaterials() => _outline.Hide();

        void OnDestroy() => _outline.Dispose();
    }
}
