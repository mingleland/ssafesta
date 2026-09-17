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

            // 부스 안에는 게임기를 두지 않는다 (사용자 지시 2026-09-10). 게임은 따로 모아 놓는 부스로 갈 예정이라
            // 전시 부스 안에 오락기가 서 있으면 성격이 섞인다. 게시본에 들어 있어도 클라이언트가 세우지 않는다 —
            // 데이터를 지우는 것이 아니라 배치만 건너뛰므로, 나중에 방침이 바뀌면 이 한 줄만 되돌리면 된다.
            if (type == BoothObjectType.GamePortal)
            {
                Debug.Log($"[BoothObjectFactory] 게임기(GAME_PORTAL, objectId={objectId})는 부스 내부에 배치하지 않는다 — 건너뜀");
                return null;
            }

            // 저작 인테리어 안에서는 AI 직원·노트북·설문·전시패널을 **스폰하지 않는다.**
            //
            // 이 넷은 12부스 인테리어에 이미 고정으로 놓여 있고, 게시본이 같은 타입을 실어 오면
            // AuthoredBoothContentBinder 가 그 configId 를 씬 오브젝트에 물린다. 여기서 또 만들면
            // 같은 부스에 저작 NPC 와 스폰된 캡슐이 나란히 서서 둘 다 F 가 먹는다 (#244, FE 보고 2026-09-18).
            // 게시본이 값의 출처인 것은 그대로다 — 배치만 건너뛰고 값은 binder 가 쓴다.
            //
            // 판정은 앵커의 루트 이름으로 한다. 저작 인테리어는 `@BoothInteriors` 아래에만 있고,
            // 그 밖(단독 검증 씬·POC 부스)에서는 지금까지처럼 스폰해야 놓인 것이 아무것도 없는 사태를 피한다.
            if (IsAuthoredInterior(anchor) && IsAuthoredContent(type))
            {
                Debug.Log($"[BoothObjectFactory] 저작 인테리어에는 {dto.type}(objectId={objectId})를 스폰하지 않는다 — 씬 오브젝트에 연결한다");
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
            WarnIfOutsideRoom(objectId, go);

            var runtimeObject = go.GetComponent<BoothRuntimeObject>();
            if (runtimeObject == null) runtimeObject = go.AddComponent<BoothRuntimeObject>();
            runtimeObject.Init(boothId, dto, type);

            // 동작을 먼저 붙이고, 그 **존재 여부로** 힌트·사거리를 결정한다.
            AttachContentBehaviour(go, type);
            AttachCommonInteraction(go, type);
            return go;
        }

        // ---------- 방 경계 검사 ----------

        // 부스 방의 실제 크기. **FestaInteriorBuilder.cs:29-31 과 같은 값이어야 한다** —
        // 그쪽은 Editor 어셈블리라 런타임에서 참조할 수 없어 여기에 다시 적는다.
        // 레이아웃 좌표의 단위는 **미터**다(앵커의 균등 스케일 13.26 이 유닛으로 바꾼다. 실측 확인:
        // 레이아웃 6 m 간격 → 월드 79.56 units = 6.00 m).
        // FestaInteriorBuilder 의 방 규격과 같은 값이라야 한다.
        const float RoomHalfXMeters = 5.0f;    // 방 폭 10 m
        const float RoomBackZMeters = -3.8f;
        const float RoomFrontZMeters = 7.0f;   // 방 깊이 10.8 m

        /// <summary>벽에 닿은 것과 뚫은 것을 가르는 여유. 셸 메시가 방 한계보다 조금 안쪽에 있어 0 으로 두면 잔소리가 된다.</summary>
        const float RoomEdgeToleranceMeters = 0.1f;

        /// <summary>
        /// 방 밖에 배치된 오브젝트를 **드러낸다**. 자르지는 않는다 — 자르면 배치가 조용히 달라져
        /// "내가 놓은 데가 아닌데" 가 되고, 원인이 스튜디오인지 런타임인지 가릴 수 없게 된다.
        ///
        /// <para>사용자는 이걸 "부스에 배치했는데 밖에 떠 있다" 로 겪는다. 2026-09-08 실측에서
        /// 계약 회귀 픽스처의 4/14 개가 방 밖이었다 — 08-31 에 방 크기를 바꾼 뒤
        /// (커밋 c7770a3a "부스 셸을 방 크기에 맞추고") 픽스처 좌표가 따라가지 않았다.
        /// 스튜디오가 방 경계로 제한하는지는 FE·BE 소관이라 별도 이슈로 올린다.</para>
        ///
        /// <para>판정은 피벗이 아니라 **실물 크기**다 (S15P21A604-657). 피벗만 보면 폭 1.55 m 짜리
        /// 프로젝트 패널을 x=+4.8 · 회전 90° 로 놓았을 때 피벗은 방 안이라 조용한데 판은 뒷벽을
        /// 0.56 m 뚫는다 — 2026-09-11 플레이 모드 실측에서 그대로 나왔다. 뚫리는 것은 피벗이 아니라 판이다.</para>
        /// </summary>
        static void WarnIfOutsideRoom(string objectId, GameObject go)
        {
            var localPos = go.transform.localPosition;
            if (!TryLocalExtents(go, out var min, out var max))
            {
                // 실물 크기를 못 재는 경우(렌더러가 없거나 스킨드 뿐)는 종전대로 피벗으로 본다.
                min = max = localPos;
            }

            float over = RoomEdgeToleranceMeters;
            float left = (-RoomHalfXMeters) - min.x, right = max.x - RoomHalfXMeters;
            float back = RoomBackZMeters - min.z, front = max.z - RoomFrontZMeters;
            if (left <= over && right <= over && back <= over && front <= over) return;

            var sides = new System.Collections.Generic.List<string>(4);
            if (left > over) sides.Add($"왼쪽 벽 {left:F2} m");
            if (right > over) sides.Add($"오른쪽 벽 {right:F2} m");
            if (back > over) sides.Add($"뒷벽 {back:F2} m");
            if (front > over) sides.Add($"앞쪽 {front:F2} m");

            Debug.LogWarning(
                $"[BoothObjectFactory] {objectId} 가 방을 벗어났다 — {string.Join(", ", sides)} 넘음. " +
                $"피벗 ({localPos.x:F2}, {localPos.z:F2}) m, 실물 x {min.x:F2}~{max.x:F2} · z {min.z:F2}~{max.z:F2} m, " +
                $"방 한계 x ±{RoomHalfXMeters} m / z {RoomBackZMeters}~{RoomFrontZMeters} m. " +
                "방문자에게는 벽을 뚫고 나가 보인다. 스튜디오에서 안쪽으로 옮겨야 한다.");
        }

        /// <summary>
        /// 오브젝트가 실제로 차지하는 범위를 **부스 로컬 미터**로 잰다.
        ///
        /// <para><see cref="SkinnedMeshRenderer"/> 는 제외한다 — 그쪽 바운즈는 bind-pose 기준이라
        /// 실제 자세보다 훨씬 크게 잡히고(팔 벌린 T 포즈), 그걸로 재면 벽 근처 NPC 마다 없는 경고가 뜬다.
        /// 스킨드만 있는 오브젝트는 false 를 돌려 호출부가 피벗으로 판정하게 둔다.</para>
        /// </summary>
        static bool TryLocalExtents(GameObject go, out Vector3 min, out Vector3 max)
        {
            min = max = Vector3.zero;
            var anchor = go.transform.parent;
            if (anchor == null) return false;

            bool any = false;
            foreach (var renderer in go.GetComponentsInChildren<MeshRenderer>(false))
            {
                var b = renderer.bounds;   // 월드 AABB
                for (int corner = 0; corner < 8; corner++)
                {
                    var world = new Vector3(
                        (corner & 1) == 0 ? b.min.x : b.max.x,
                        (corner & 2) == 0 ? b.min.y : b.max.y,
                        (corner & 4) == 0 ? b.min.z : b.max.z);
                    var local = anchor.InverseTransformPoint(world);
                    if (!any) { min = max = local; any = true; continue; }
                    min = Vector3.Min(min, local);
                    max = Vector3.Max(max, local);
                }
            }
            return any;
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
            // 가구·장식은 조준 대상이 아니다 — 화분을 보면 '전시물 · 준비 중' 이 떠서 영원히 준비 중인 물건처럼 읽혔다
            // (QA 2026-09-08 #48). 동작이 없는 장식에는 안내 알약도 하이라이트도 붙이지 않는다. 콜라이더는 프리팹 것으로 충분.
            if (!interactive && (type == BoothObjectType.Furniture || type == BoothObjectType.Decoration)) return;
            var target = go.GetComponent<BoothInteractionTarget>();
            if (target == null) target = go.AddComponent<BoothInteractionTarget>();
            // 사거리는 **월드 유닛**이고 판정은 콜라이더 **표면** 기준이다
            // (BoothInteractionTarget.DistanceFrom). 표면 기준이라 값이 오브젝트 크기와
            // 무관해져, "거의 붙어야 잡힌다" 를 크기가 제각각인 대상 전부에 한 숫자로 건다.
            // 20f ≈ 1.5 m, 15f ≈ 1.1 m (부스 스케일 1 m ≈ 13.26 unit). 13f 는 표면 기준이어도
            // 큰 오브젝트 앞에서 닿지 않는다는 보고가 있어 올렸다.
            // 전에는 피벗 기준 40f 라 3 m 밖에서도 잡혀 "범위가 너무 크다" 는 보고를 받았다.
            // 2026-09-10 사용자 지시로 "거의 외곽에 붙었을 때만" 으로 줄였다 (20/15 → 12/9).
            // 판정이 **수평 거리**로 바뀌었으므로(BoothInteractionTarget.DistanceFrom) 12u ≈ 0.9 m 다.
            // 더 줄이지 못하는 이유는 실측이다: 노트북은 책상 안쪽(앞면에서 7.9u)에 놓여 있고 플레이어 캡슐
            // 반경이 2.75u 라, 책상에 몸이 닿아도 노트북까지 10.7u 다. 8u 로 두면 손이 닿는 자리에서 F 가 안 먹는다.
            target.Configure(interactive ? 12f : 9f, interactive);
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
                    // 영상 스크린은 **장식**이라 이미지까지만 — 영상은 전시 패널이 튼다.
                    // 한 부스에 화면이 둘이면 둘 다 틀려 해서 서로 깜빡인다.
                    BoothScreenSurface.Attach(go, allowVideo: false);
                    break;
                case BoothObjectType.Laptop:
                    if (go.GetComponent<LaptopInteractable>() == null)
                        go.AddComponent<LaptopInteractable>();
                    break;
                case BoothObjectType.ProjectPanel:
                    if (go.GetComponent<ProjectPanelInteractable>() == null)
                        go.AddComponent<ProjectPanelInteractable>();
                    // 전시 패널이 부스의 큰 화면이다 — 영상 → 로고 → 썸네일 → 검은 화면.
                    BoothScreenSurface.Attach(go, allowVideo: true);
                    break;
                case BoothObjectType.SurveyKiosk:
                    if (go.GetComponent<SurveyKioskInteractable>() == null)
                        go.AddComponent<SurveyKioskInteractable>();
                    break;
                // 이후 타입별 컴포넌트는 해당 기능 spec 작성 후 추가한다.
                // CONSULTATION_DESK 는 아직 붙이지 않는다 — 사람 상담의 시작점은 AI 대화
                // 에스컬레이션이다(spec 011 FR-005, S15P21A604-416). 데스크 진입은 후속.
            }
        }

        /// <summary>앵커가 12부스 저작 인테리어(<c>@BoothInteriors</c>) 안에 있는가.</summary>
        static bool IsAuthoredInterior(Transform anchor) =>
            anchor != null && anchor.root != null && anchor.root.name == "@BoothInteriors";

        /// <summary>인테리어에 이미 놓여 있어 게시본으로 또 만들면 안 되는 넷.</summary>
        static bool IsAuthoredContent(BoothObjectType type) =>
            type == BoothObjectType.AiAgent
            || type == BoothObjectType.Laptop
            || type == BoothObjectType.SurveyKiosk
            || type == BoothObjectType.ProjectPanel;
    }
}
