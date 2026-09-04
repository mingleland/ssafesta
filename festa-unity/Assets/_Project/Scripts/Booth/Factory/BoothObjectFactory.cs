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
            var objectId = dto.ResolvedObjectId;
            if (type == BoothObjectType.Unknown)
            {
                // 알 수 없는 타입은 클라이언트를 깨뜨리지 않고 스킵한다 (forward compat).
                Debug.LogWarning($"[BoothObjectFactory] Unknown type '{dto.type}' (objectId={objectId}) — skipped");
                return null;
            }

            var prefab = _registry != null ? _registry.GetPrefab(type, dto.assetCode) : null;
            if (prefab == null && type == BoothObjectType.Laptop)
            {
                // 노트북은 정식 Free Laptop Prefab만 사용한다. 임시 큐브로 대체하면
                // 실제 상호작용/표현 오류를 숨기므로 이 오브젝트만 건너뛴다.
                Debug.LogError($"[BoothObjectFactory] Laptop prefab missing (objectId={objectId}) — skipped");
                return null;
            }

            float groundLift = 0f; // 프리팹은 피벗을 바닥 기준으로 제작한다고 가정
            var go = prefab != null
                ? Object.Instantiate(prefab, anchor)
                : CreatePlaceholder(type, anchor, out groundLift);

            go.name = $"Booth{boothId}_{objectId}";
            go.transform.localPosition =
                (dto.position?.ToVector3() ?? Vector3.zero) + Vector3.up * groundLift;
            go.transform.localRotation = Quaternion.Euler(0f, dto.rotationY, 0f);

            var runtimeObject = go.GetComponent<BoothRuntimeObject>();
            if (runtimeObject == null) runtimeObject = go.AddComponent<BoothRuntimeObject>();
            runtimeObject.Init(boothId, dto, type);

            // 동작을 먼저 붙이고, 그 **존재 여부로** 힌트·사거리를 결정한다.
            AttachContentBehaviour(go, type);
            AttachCommonInteraction(go, type);
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
                BoothObjectType.LikeVote => (PrimitiveType.Sphere, new Color(1f, 0.4f, 0.5f), Vector3.one * 0.5f),
                _ => (PrimitiveType.Cube, Color.gray, Vector3.one * 0.8f),
            };

            var go = GameObject.CreatePrimitive(primitive);
            go.transform.SetParent(anchor, false);
            go.transform.localScale = scale;

            // 피벗이 중심이므로 도형 높이의 절반만큼 올려야 바닥 위에 선다.
            // (Capsule 기본 높이 2 → 절반 = scale.y, Cube/Sphere 기본 높이 1 → 절반 = scale.y * 0.5)
            groundLift = primitive == PrimitiveType.Capsule ? scale.y : scale.y * 0.5f;

            // 서버는 그리지 않는다 (S15P21A604-314). 여기서부터는 전부 시각 요소다 —
            // 머티리얼·셰이더·폰트. 셰이더가 스트립된 서버 빌드에서는 만들 때마다
            // "Trying to access a shader…" 경고가 부스 수만큼 반복돼 실제 로그를 덮는다.
            //
            // **콜라이더와 트랜스폼은 그대로 둔다.** CreatePrimitive 가 붙여준 콜라이더가
            // 서버의 충돌 권위다 — 이걸 같이 걷어내면 벽 뚫림이 서버 쪽에서 재발한다.
            if (Festa.Core.HeadlessRuntime.IsHeadless) return go;

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
            // "상호작용 가능" 은 타입 분류가 아니라 **실제로 F 에 응답하는 컴포넌트의 존재**다.
            // 전에는 가구·장식만 빼고 전부 true 라서, 책상·키오스크처럼 동작이 아직 없는
            // 오브젝트에도 "F — 상호작용" 힌트가 떴고 F 는 조용히 무시됐다 — 힌트가 거짓말을
            // 하면 동작하는 오브젝트까지 의심받는다 (S15P21A604-345 실측).
            bool interactive = go.GetComponentInChildren<Festa.Content.IBoothInteractable>(true) != null;
            var target = go.GetComponent<BoothInteractionTarget>();
            if (target == null) target = go.AddComponent<BoothInteractionTarget>();
            // 사거리는 **월드 유닛**이고 판정은 콜라이더 **표면** 기준이다
            // (BoothInteractionTarget.DistanceFrom). 표면 기준이라 값이 오브젝트 크기와
            // 무관해져, "거의 붙어야 잡힌다" 를 크기가 제각각인 대상 전부에 한 숫자로 건다.
            // 20f ≈ 1.5 m, 15f ≈ 1.1 m (부스 스케일 1 m ≈ 13.26 unit). 13f 는 표면 기준이어도
            // 큰 오브젝트 앞에서 닿지 않는다는 보고가 있어 올렸다.
            // 전에는 피벗 기준 40f 라 3 m 밖에서도 잡혀 "범위가 너무 크다" 는 보고를 받았다.
            target.Configure(interactive ? 20f : 15f, interactive);
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
