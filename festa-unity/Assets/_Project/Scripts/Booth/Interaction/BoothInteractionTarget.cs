using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>부스 프리팹의 클릭 범위와 비파괴 하이라이트를 통일한다.</summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteractionTarget : MonoBehaviour
    {
        static readonly int EmissionColor = Shader.PropertyToID("_EmissionColor");

        /// <summary>판정 거리. **월드 유닛**이다 (1 m = 10 unit) — 30f 는 3 m 다.</summary>
        [SerializeField, Min(0.5f)] float _maxDistance = 30f;
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
