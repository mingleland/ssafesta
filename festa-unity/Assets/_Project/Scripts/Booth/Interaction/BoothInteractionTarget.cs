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
        [Tooltip("강조 색. 발밑 링(금색)과 같은 계열이라야 같은 기능으로 읽힌다.")]
        [SerializeField] Color _highlightColor = new(1f, 0.82f, 0.35f, 1f);

        [Tooltip("발광 세기 — 너무 높이면 재질 색이 날아가 형태를 알아볼 수 없다.")]
        [SerializeField, Range(0.1f, 3f)] float _highlightStrength = 0.9f;

        readonly List<Renderer> _renderers = new();
        // 하이라이트는 **재질 인스턴스**로 건다. MaterialPropertyBlock 으로 _EmissionColor 만
        // 써 넣던 이전 방식은 **셰이더 키워드를 켤 수 없어**, 재질에 _EMISSION 이 꺼져 있으면
        // 아무 일도 일어나지 않았다 — 하이라이트가 조용히 죽어 있었다 (S15P21A604-355).
        // 강조 대상은 항상 하나뿐이라 인스턴스 비용은 무시할 수 있다.
        readonly List<Material[]> _originalMaterials = new();
        readonly List<Material> _instanced = new();
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
        // 전에는 렌더러 재질을 인스턴스로 바꿔 에미션을 켰다 — prefab 전체(관리 데스크의 NPC+테이블)가
        // 노랗게 빛나 "선택" 이 아니라 "발광" 으로 보였다. 이제는 대상 부위 렌더러마다 같은 메시를
        // 노멀 방향으로 밀어 낸 **앞면 컬링 복제 렌더러**를 자식으로 붙여 테두리만 그린다(Festa/Outline).
        // 원본 재질은 건드리지 않으므로 재질 누수·복원 실패 계열의 문제가 사라진다.
        [Tooltip("이 트랜스폼 아래 렌더러에만 외곽선을 건다. 비우면 대상 전체 — 관리 데스크처럼 NPC+테이블이 " +
                 "한 대상이면 NPC 쪽 트랜스폼을 지정한다.")]
        [SerializeField] Transform _highlightRoot;
        [SerializeField, Range(0.05f, 2f)] float _outlineWidth = 0.35f;   // 월드 유닛 (1 m = 13.26)
        static Material s_outlineMaterialTemplate;
        Material _outlineMaterial;
        readonly List<GameObject> _outlineObjects = new();
        const string OutlineObjectName = "__FestaOutline";

        /// <summary>외곽선을 걸 트랜스폼을 코드에서 정한다(팩토리·씬 배치 양쪽).</summary>
        public void SetHighlightRoot(Transform root) => _highlightRoot = root;

        void ApplyHighlightMaterials()
        {
            if (_outlineMaterial == null)
            {
                if (s_outlineMaterialTemplate == null)
                {
                    var shader = Shader.Find("Festa/Outline");
                    if (shader == null)
                    {
                        Debug.LogError("[BoothInteractionTarget] Festa/Outline 셰이더를 찾지 못했다 — Resources/Shaders 에 있어야 빌드에 포함된다. 하이라이트 없이 진행한다.");
                        return;
                    }
                    s_outlineMaterialTemplate = new Material(shader);
                }
                _outlineMaterial = new Material(s_outlineMaterialTemplate);
                _outlineMaterial.SetColor("_Color", _highlightColor);
                _outlineMaterial.SetFloat("_Width", _outlineWidth);
                _instanced.Add(_outlineMaterial);
            }

            var root = _highlightRoot != null ? _highlightRoot : transform;
            foreach (var r in root.GetComponentsInChildren<Renderer>(false))
            {
                if (r == null || r.gameObject.name == OutlineObjectName) continue;
                if (r is ParticleSystemRenderer || r is LineRenderer || r is TrailRenderer) continue;

                var go = new GameObject(OutlineObjectName);
                go.transform.SetParent(r.transform, false);
                go.layer = r.gameObject.layer;

                if (r is SkinnedMeshRenderer skinned)
                {
                    var copy = go.AddComponent<SkinnedMeshRenderer>();
                    copy.sharedMesh = skinned.sharedMesh;
                    copy.bones = skinned.bones;
                    copy.rootBone = skinned.rootBone;
                    copy.localBounds = skinned.localBounds;
                    copy.quality = skinned.quality;
                    copy.updateWhenOffscreen = skinned.updateWhenOffscreen;
                    copy.sharedMaterials = Repeat(_outlineMaterial, skinned.sharedMaterials.Length);
                    copy.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                    copy.receiveShadows = false;
                }
                else if (r is MeshRenderer && r.TryGetComponent<MeshFilter>(out var filter) && filter.sharedMesh != null)
                {
                    go.AddComponent<MeshFilter>().sharedMesh = filter.sharedMesh;
                    var copy = go.AddComponent<MeshRenderer>();
                    copy.sharedMaterials = Repeat(_outlineMaterial, r.sharedMaterials.Length);
                    copy.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                    copy.receiveShadows = false;
                }
                else
                {
                    Destroy(go);
                    continue;
                }
                _outlineObjects.Add(go);
            }
        }

        static Material[] Repeat(Material m, int count)
        {
            var arr = new Material[Mathf.Max(1, count)];
            for (int i = 0; i < arr.Length; i++) arr[i] = m;
            return arr;
        }

        void RestoreMaterials()
        {
            foreach (var go in _outlineObjects) if (go != null) Destroy(go);
            _outlineObjects.Clear();
            _originalMaterials.Clear();
            // 만든 인스턴스는 반드시 지운다 — 강조할 때마다 새로 만들면 재질이 샌다.
            foreach (var m in _instanced) if (m != null) Destroy(m);
            _instanced.Clear();
            _outlineMaterial = null;
        }

        void OnDestroy() => RestoreMaterials();
    }
}
