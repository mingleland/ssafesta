// 상호작용 대상 외곽선 하이라이트 — 부스 오브젝트(BoothInteractionTarget)와 부스 입장 포털(PortalInteractor)이 함께 쓴다.
//
// 왜 공용인가: 원래 이 로직은 BoothInteractionTarget 안에만 있었다. 2026-09-10 사용자 요청으로 부스 구조물에도
// 같은 외곽선을 켜게 되면서 두 벌이 될 뻔했다 — 외곽선은 "상호작용할 수 있다" 는 한 가지 뜻이므로 표현도 하나여야 한다.
//
// 방식: 대상 렌더러마다 복제 렌더러를 **둘** 붙인다.
//   ① 마스크(`__FestaOutlineMask`) — 색을 쓰지 않고 대상이 보이는 픽셀에 스텐실 비트만 찍는다(Queue Geometry+5).
//   ② 껍질(`__FestaOutline`)     — 메시를 노멀 방향으로 밀어 앞면을 컬링해 그리되, 마스크가 찍힌 픽셀은 건너뛴다(Queue Geometry+10).
// 이렇게 해야 **최외곽 실루엣만** 남는다. ①이 없으면 부스 안쪽 소품(오리·풍선)마다 테두리가 서서
// 부스 전체가 네온처럼 보였다 (2026-09-10 사용자 지적). 자세한 이유는 FestaOutline.shader 주석 참조.
//
// 원본 재질은 건드리지 않으므로 재질 인스턴스 누수·복원 실패가 없다 (S15P21A604-437).
using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>대상 트랜스폼 아래 렌더러에 외곽선 복제본을 붙였다 뗀다. MonoBehaviour 가 아니라 소유자가 필드로 들고 쓴다.</summary>
    public sealed class OutlineHighlighter
    {
        const string OutlineObjectName = "__FestaOutline";
        const string MaskObjectName = "__FestaOutlineMask";

        // 셰이더 프로퍼티 이름 — FestaOutline.shader 와 짝이다.
        static readonly int ColorId = Shader.PropertyToID("_Color");
        static readonly int WidthId = Shader.PropertyToID("_Width");
        static readonly int CullId = Shader.PropertyToID("_Cull");
        static readonly int ColorMaskId = Shader.PropertyToID("_ColorMask");
        static readonly int ZWriteId = Shader.PropertyToID("_ZWrite");
        static readonly int StencilCompId = Shader.PropertyToID("_StencilComp");
        static readonly int StencilOpId = Shader.PropertyToID("_StencilOp");

        static Material s_template;

        readonly List<GameObject> _objects = new();
        Material _hullMaterial;
        Material _maskMaterial;
        Transform _root;

        public bool Visible => _root != null;

        /// <summary>
        /// <paramref name="root"/> 아래 렌더러에 외곽선을 건다. 이미 같은 root 에 켜져 있으면 아무것도 하지 않는다.
        /// root 가 바뀌면 이전 것을 지우고 새로 만든다. root 가 null 이면 <see cref="Hide"/> 와 같다.
        /// </summary>
        public void Show(Transform root, Color color, float width)
        {
            if (root == null) { Hide(); return; }
            if (ReferenceEquals(_root, root)) return;
            Hide();

            if (!EnsureMaterials()) return;
            _hullMaterial.SetColor(ColorId, color);
            _hullMaterial.SetFloat(WidthId, width);

            foreach (var r in root.GetComponentsInChildren<Renderer>(false))
            {
                if (r == null) continue;
                var n = r.gameObject.name;
                if (n == OutlineObjectName || n == MaskObjectName) continue;
                if (r is ParticleSystemRenderer || r is LineRenderer || r is TrailRenderer) continue;

                // 마스크가 먼저(큐 2005), 껍질이 나중(큐 2010). 순서는 재질의 renderQueue 가 정한다.
                Duplicate(r, MaskObjectName, _maskMaterial);
                Duplicate(r, OutlineObjectName, _hullMaterial);
            }
            _root = root;
        }

        void Duplicate(Renderer source, string name, Material material)
        {
            var go = new GameObject(name);
            go.transform.SetParent(source.transform, false);
            go.layer = source.gameObject.layer;

            if (source is SkinnedMeshRenderer skinned)
            {
                var copy = go.AddComponent<SkinnedMeshRenderer>();
                copy.sharedMesh = skinned.sharedMesh;
                copy.bones = skinned.bones;
                copy.rootBone = skinned.rootBone;
                copy.localBounds = skinned.localBounds;
                copy.quality = skinned.quality;
                copy.updateWhenOffscreen = skinned.updateWhenOffscreen;
                copy.sharedMaterials = Repeat(material, skinned.sharedMaterials.Length);
                copy.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                copy.receiveShadows = false;
            }
            else if (source is MeshRenderer && source.TryGetComponent<MeshFilter>(out var filter) && filter.sharedMesh != null)
            {
                go.AddComponent<MeshFilter>().sharedMesh = filter.sharedMesh;
                var copy = go.AddComponent<MeshRenderer>();
                copy.sharedMaterials = Repeat(material, source.sharedMaterials.Length);
                copy.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                copy.receiveShadows = false;
            }
            else
            {
                Object.Destroy(go);
                return;
            }
            _objects.Add(go);
        }

        bool EnsureMaterials()
        {
            if (_hullMaterial != null && _maskMaterial != null) return true;

            if (s_template == null)
            {
                var shader = Shader.Find("Festa/Outline");
                if (shader == null)
                {
                    // 조용히 넘어가지 않는다 — 하이라이트가 죽어 있으면 "상호작용이 안 된다" 로 읽힌다 (T-24 원칙).
                    Debug.LogError("[OutlineHighlighter] Festa/Outline 셰이더를 찾지 못했다 — Resources/Shaders 에 있어야 빌드에 포함된다.");
                    return false;
                }
                s_template = new Material(shader);
            }

            // 껍질: 앞면 컬링, 색을 쓰고, 스텐실 비트가 **없는** 곳에만 그린다(NotEqual=6, Keep=0).
            _hullMaterial = new Material(s_template);
            _hullMaterial.SetFloat(CullId, 1f);        // Front
            _hullMaterial.SetFloat(ColorMaskId, 15f);  // RGBA
            _hullMaterial.SetFloat(ZWriteId, 1f);
            _hullMaterial.SetFloat(StencilCompId, 6f); // NotEqual
            _hullMaterial.SetFloat(StencilOpId, 0f);   // Keep
            _hullMaterial.renderQueue = 2010;

            // 마스크: 원본 그대로(두께 0), 뒷면 컬링, 색 안 씀, 보이는 픽셀에 스텐실 비트를 찍는다(Always=8, Replace=2).
            _maskMaterial = new Material(s_template);
            _maskMaterial.SetFloat(WidthId, 0f);
            _maskMaterial.SetFloat(CullId, 2f);        // Back
            _maskMaterial.SetFloat(ColorMaskId, 0f);   // 색 안 씀
            _maskMaterial.SetFloat(ZWriteId, 0f);
            _maskMaterial.SetFloat(StencilCompId, 8f); // Always
            _maskMaterial.SetFloat(StencilOpId, 2f);   // Replace
            _maskMaterial.renderQueue = 2005;
            return true;
        }

        public void Hide()
        {
            foreach (var go in _objects) if (go != null) Object.Destroy(go);
            _objects.Clear();
            _root = null;
        }

        /// <summary>소유자가 파괴될 때 부른다 — 만든 재질까지 지운다.</summary>
        public void Dispose()
        {
            Hide();
            if (_hullMaterial != null) { Object.Destroy(_hullMaterial); _hullMaterial = null; }
            if (_maskMaterial != null) { Object.Destroy(_maskMaterial); _maskMaterial = null; }
        }

        static Material[] Repeat(Material m, int count)
        {
            var arr = new Material[Mathf.Max(1, count)];
            for (int i = 0; i < arr.Length; i++) arr[i] = m;
            return arr;
        }
    }
}
