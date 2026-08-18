using System.Collections.Generic;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>부스 프리팹의 클릭 범위와 비파괴 하이라이트를 통일한다.</summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteractionTarget : MonoBehaviour
    {
        static readonly int EmissionColor = Shader.PropertyToID("_EmissionColor");

        [SerializeField, Min(0.5f)] float _maxDistance = 3f;
        [SerializeField] bool _highlightEnabled = true;
        [SerializeField] Color _highlightColor = new(0.25f, 0.7f, 1f, 1f);

        readonly List<Renderer> _renderers = new();
        MaterialPropertyBlock _block;

        public float MaxDistance => _maxDistance;

        public void Configure(float maxDistance, bool highlightEnabled)
        {
            _maxDistance = Mathf.Max(0.5f, maxDistance);
            _highlightEnabled = highlightEnabled;
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
        }

        void OnMouseEnter() => SetHighlighted(true);
        void OnMouseExit() => SetHighlighted(false);

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
