using Festa.Content;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// DTO 하나 → GameObject 하나.
    /// Registry에 프리팹이 있으면 사용하고, 없으면 placeholder primitive를 생성한다
    /// (아트/프리팹 없이도 POC B 검증 가능).
    /// Booth Object는 NetworkObject가 아니다 — 각 클라이언트 Local Spawn (doc 07 §6).
    /// </summary>
    public class BoothObjectFactory
    {
        readonly BoothObjectRegistry _registry;

        public BoothObjectFactory(BoothObjectRegistry registry) => _registry = registry;

        public GameObject Create(int boothId, BoothObjectDto dto, Transform anchor)
        {
            var type = BoothObjectTypes.Parse(dto.type);
            if (type == BoothObjectType.Unknown)
            {
                // 알 수 없는 타입은 클라이언트를 깨뜨리지 않고 스킵한다 (forward compat).
                Debug.LogWarning($"[BoothObjectFactory] Unknown type '{dto.type}' (id={dto.id}) — skipped");
                return null;
            }

            var prefab = _registry != null ? _registry.GetPrefab(type) : null;
            float groundLift = 0f; // 프리팹은 피벗을 바닥 기준으로 제작한다고 가정
            var go = prefab != null
                ? Object.Instantiate(prefab, anchor)
                : CreatePlaceholder(type, anchor, out groundLift);

            go.name = $"Booth{boothId}_{dto.id}";
            go.transform.localPosition =
                (dto.position?.ToVector3() ?? Vector3.zero) + Vector3.up * groundLift;
            go.transform.localRotation = Quaternion.Euler(0f, dto.rotationY, 0f);

            var runtimeObject = go.GetComponent<BoothRuntimeObject>();
            if (runtimeObject == null) runtimeObject = go.AddComponent<BoothRuntimeObject>();
            runtimeObject.Init(boothId, dto, type);

            AttachCommonInteraction(go, type);
            AttachContentBehaviour(go, type);
            return go;
        }

        // ---------- Placeholder ----------

        static GameObject CreatePlaceholder(BoothObjectType type, Transform anchor, out float groundLift)
        {
            var (primitive, color, scale) = type switch
            {
                BoothObjectType.AiAgent => (PrimitiveType.Capsule, new Color(0.4f, 0.7f, 1f), new Vector3(0.6f, 1f, 0.6f)),
                BoothObjectType.VideoScreen => (PrimitiveType.Cube, Color.black, new Vector3(2.4f, 1.4f, 0.1f)),
                BoothObjectType.ProjectPanel => (PrimitiveType.Cube, new Color(0.9f, 0.9f, 0.8f), new Vector3(1.2f, 1.6f, 0.08f)),
                BoothObjectType.SurveyKiosk => (PrimitiveType.Cube, new Color(0.5f, 1f, 0.6f), new Vector3(0.5f, 1.2f, 0.5f)),
                BoothObjectType.RecruitmentBoard => (PrimitiveType.Cube, new Color(1f, 0.8f, 0.4f), new Vector3(1.4f, 1.8f, 0.08f)),
                BoothObjectType.ConsultationDesk => (PrimitiveType.Cube, new Color(0.6f, 0.4f, 0.2f), new Vector3(1.6f, 0.8f, 0.8f)),
                BoothObjectType.Laptop => (PrimitiveType.Cube, new Color(0.15f, 0.2f, 0.3f), new Vector3(0.7f, 0.08f, 0.5f)),
                BoothObjectType.LikeVote => (PrimitiveType.Sphere, new Color(1f, 0.4f, 0.5f), Vector3.one * 0.5f),
                _ => (PrimitiveType.Cube, Color.gray, Vector3.one * 0.8f),
            };

            var go = GameObject.CreatePrimitive(primitive);
            go.transform.SetParent(anchor, false);
            go.transform.localScale = scale;

            // 피벗이 중심이므로 도형 높이의 절반만큼 올려야 바닥 위에 선다.
            // (Capsule 기본 높이 2 → 절반 = scale.y, Cube/Sphere 기본 높이 1 → 절반 = scale.y * 0.5)
            groundLift = primitive == PrimitiveType.Capsule ? scale.y : scale.y * 0.5f;

            var renderer = go.GetComponent<Renderer>();
            if (renderer != null)
            {
                // WebGL 빌드에서 CreatePrimitive 기본 머티리얼이 마젠타로 깨지는 문제 대응:
                // 씬 머티리얼(Plane 등)이 이미 포함시킨 URP Lit 셰이더로 직접 생성한다.
                var urpLit = Shader.Find("Universal Render Pipeline/Lit");
                if (urpLit != null)
                    renderer.material = new Material(urpLit) { color = color };
                else
                    renderer.material.color = color; // fallback (에디터 등)
            }

            // placeholder 라벨 — 부모가 비균등 스케일이므로 역스케일로 왜곡 보정
            var labelGo = new GameObject("Label");
            labelGo.transform.SetParent(go.transform, false);
            float localTop = primitive == PrimitiveType.Capsule ? 1f : 0.5f; // 스케일 전 로컬 상단
            labelGo.transform.localPosition = Vector3.up * (localTop + 0.4f / Mathf.Max(scale.y, 0.01f));
            labelGo.transform.localScale = new Vector3(
                1f / Mathf.Max(scale.x, 0.01f),
                1f / Mathf.Max(scale.y, 0.01f),
                1f / Mathf.Max(scale.z, 0.01f));
            var label = labelGo.AddComponent<TextMesh>();

            // Unity 6: 런타임 생성 TextMesh는 폰트를 직접 지정해야 한다 (미지정 시 글리치 렌더링)
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            label.font = font;
            labelGo.GetComponent<MeshRenderer>().material = font.material;

            label.text = type.ToString();
            label.characterSize = 0.1f;
            label.fontSize = 40;
            label.anchor = TextAnchor.MiddleCenter;

            return go;
        }

        // ---------- Content Behaviour 연결 ----------

        static void AttachCommonInteraction(GameObject go, BoothObjectType type)
        {
            bool interactive = type is not BoothObjectType.Furniture and not BoothObjectType.Decoration;
            var target = go.GetComponent<BoothInteractionTarget>();
            if (target == null) target = go.AddComponent<BoothInteractionTarget>();
            target.Configure(interactive ? 3f : 2.2f, interactive);
        }

        static void AttachContentBehaviour(GameObject go, BoothObjectType type)
        {
            switch (type)
            {
                case BoothObjectType.AiAgent:
                    if (go.GetComponent<AiNpcInteractable>() == null)
                        go.AddComponent<AiNpcInteractable>();
                    break;
                case BoothObjectType.VideoScreen:
                    if (go.GetComponent<VideoScreenPlaceholder>() == null)
                        go.AddComponent<VideoScreenPlaceholder>();
                    break;
                case BoothObjectType.Laptop:
                    if (go.GetComponent<LaptopInteractable>() == null)
                        go.AddComponent<LaptopInteractable>();
                    break;
                // 이후 타입별 컴포넌트는 해당 기능 spec 작성 후 추가한다.
            }
        }
    }
}
