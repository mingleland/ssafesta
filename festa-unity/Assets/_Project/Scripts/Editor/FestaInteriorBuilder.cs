using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 내부 부스 공간 12실 생성기 v3 (S15P21A604-445). 외부 FestivalSlot_XX 와 번호(boothId 1~12)로 1:1 연결.
    ///
    /// <para><b>규격의 단일 출처는 FE·BE 계약이다</b> — 부스는 6 × 6 × 2.72 m(ExpoKit 셸 실측), 레이아웃 좌표는 부스 로컬 미터,
    /// 정면(방문객이 들어오는 쪽)은 +z (FE passage.ts: "정면만 개방, 좌·우·후면은 벽"). 앵커 스케일 = 월드 규약 1 m = 13.26 unit.
    /// 셸은 로컬 스케일 1 로 두어 6.45 × 6.17 m 발판이 그대로 6 m 격자에 맞는다.</para>
    ///
    /// <para>v2 는 방 16 m·천장 9 m·셸 2.4배였다 — 6 m 레이아웃이 큰 홀 가운데 오도카니 놓이고, 출구 포털이 셸 뒤 벽에 있어
    /// 보이지도 않았다(사용자 지적 2026-09-06). v3 는 전시회 부스처럼: 셸 둘레 1.8 m 통로, 정면 3 m 통로, 천장 3.4 m,
    /// 정면 벽에 **문틀 + 발광 EXIT 사인 + 바닥 매트**, 스폰은 문 안쪽에서 부스를 바라본다. 조명은 부스 위 4 등 + 통로 1 등.</para>
    ///
    /// <para>NetworkObject 없음 (Booth 정적 오브젝트 Local Spawn 원칙). 외부 포털(Festival.prefab 안)은 건드리지 않는다 —
    /// 목적지는 이름 규약(Interior_NN/SpawnPoint, ReturnPoint_NN)으로 런타임에 해결된다(-330).</para>
    /// </summary>
    public static class FestaInteriorBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string ShellPrefabPath = "Assets/_Project/Prefabs/Booth/BoothShell.prefab";
        const string RegistryPath = "Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset";

        const float M = 13.26f;                 // 1 m
        const float AnchorScale = M;            // 부스 로컬 1 m = 월드 13.26 unit (레이아웃 좌표 = 미터)
        const float RoomHalfX = 5.0f * M;       // 방 폭 10 m (셸 ±3.2 m + 통로 1.8 m)
        const float RoomBackZ = -3.8f * M;      // 셸 뒷벽(-3.0 m) 뒤 0.8 m
        const float RoomFrontZ = 7.0f * M;      // 셸 앞선(+3.15 m) 앞 3.85 m 통로 — 추적 카메라 기본 1.7 m·최대 3.4 m 가 벽에 눌리지 않게
        const float WallH = 3.4f * M;           // 천장 3.4 m (셸 2.72 m + 여유)
        const float WallT = 0.3f * M;
        const float Pitch = 700f;               // 방 간격 70 m (v2 와 같음 — 외부 포털 목적지 이름만 쓰므로 위치는 자유)

        [MenuItem("Festa/World/내부 부스 공간 재생성 (v3)")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Interior] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var fest = GameObject.Find("/@Festival");
            var slots = fest != null ? fest.transform.Find("Festival_Slots") : null;
            if (slots == null) { Debug.LogError("[Interior] Festival_Slots 없음"); return; }

            var shellPrefab = AssetDatabase.LoadAssetAtPath<GameObject>(ShellPrefabPath);
            var registry = AssetDatabase.LoadAssetAtPath<ScriptableObject>(RegistryPath);
            if (shellPrefab == null || registry == null)
            { Debug.LogError($"[Interior] 셸({shellPrefab != null}) 또는 레지스트리({registry != null}) 없음"); return; }

            var oldRoot = GameObject.Find("/@BoothInteriors");
            if (oldRoot != null) Object.DestroyImmediate(oldRoot);
            var root = new GameObject("@BoothInteriors");

            var wallMat = Mat("InteriorWall", new Color(0.93f, 0.92f, 0.89f), 0.05f);
            var floorMat = Mat("InteriorFloor", new Color(0.80f, 0.78f, 0.74f), 0.15f);
            var trimMat = Mat("InteriorTrim", new Color(0.22f, 0.23f, 0.27f), 0.3f);
            var matMat = Mat("InteriorDoorMat", new Color(0.30f, 0.32f, 0.38f), 0.1f);
            var exitMat = EmissiveMat("InteriorExitSign", new Color(0.35f, 0.95f, 0.55f), 2.2f);
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

            int i = 0;
            foreach (Transform slot in slots)
            {
                i++;
                int col = (i - 1) % 4, row = (i - 1) / 4;
                var center = new Vector3(700f + col * Pitch, 0f, row * Pitch);
                BuildRoom(root.transform, center, i, wallMat, floorMat, trimMat, matMat, exitMat, font, shellPrefab, registry);
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(root.scene);
            Debug.Log($"[Interior] v3 — 방 {i}실 (10 × 10 m, 천장 3.4 m, 셸 6 m 스케일 1, 앵커 {AnchorScale:F2}) + 내부 포털 {i}개 — 씬 저장 필요");
        }

        static void BuildRoom(Transform parent, Vector3 c, int id, Material wall, Material floor, Material trim, Material doorMat,
                              Material exitSign, Font font, GameObject shellPrefab, ScriptableObject registry)
        {
            var room = new GameObject($"Interior_{id:D2}");
            room.transform.SetParent(parent, false);
            room.transform.position = c;

            GameObject Box(string name, Vector3 pos, Vector3 size, Material m, bool castShadow, bool collider = true)
            {
                var b = GameObject.CreatePrimitive(PrimitiveType.Cube);
                b.name = name;
                b.transform.SetParent(room.transform, false);
                b.transform.localPosition = pos;
                b.transform.localScale = size;
                if (!collider) Object.DestroyImmediate(b.GetComponent<Collider>());
                var r = b.GetComponent<Renderer>();
                r.sharedMaterial = m;
                r.shadowCastingMode = castShadow
                    ? UnityEngine.Rendering.ShadowCastingMode.On
                    : UnityEngine.Rendering.ShadowCastingMode.Off;
                GameObjectUtility.SetStaticEditorFlags(b, StaticEditorFlags.OccludeeStatic);   // 배칭 금지 (T-191)
                return b;
            }

            float zc = (RoomBackZ + RoomFrontZ) * 0.5f;          // 방 중심 z (부스 원점 기준 +1.2 m)
            float depth = RoomFrontZ - RoomBackZ;                // 10 m
            float width = RoomHalfX * 2f;                        // 10 m

            Box("Floor", new Vector3(0, -WallT / 2f, zc), new Vector3(width, WallT, depth), floor, false);
            Box("Ceiling", new Vector3(0, WallH + WallT / 2f, zc), new Vector3(width, WallT, depth), wall, false);
            Box("Wall_Back", new Vector3(0, WallH / 2f, RoomBackZ - WallT / 2f), new Vector3(width, WallH, WallT), wall, true);
            Box("Wall_E", new Vector3(RoomHalfX + WallT / 2f, WallH / 2f, zc), new Vector3(WallT, WallH, depth), wall, true);
            Box("Wall_W", new Vector3(-RoomHalfX - WallT / 2f, WallH / 2f, zc), new Vector3(WallT, WallH, depth), wall, true);

            // 정면 벽은 문 자리(폭 2.2 m)를 비우고 좌우 두 장으로 세운다 — 문 너머는 어두운 매트(밖으로 나가는 느낌).
            float doorW = 2.2f * M, doorH = 2.4f * M;
            float sideW = (width - doorW) / 2f;
            Box("Wall_Front_L", new Vector3(-(doorW / 2f + sideW / 2f), WallH / 2f, RoomFrontZ + WallT / 2f), new Vector3(sideW, WallH, WallT), wall, true);
            Box("Wall_Front_R", new Vector3(doorW / 2f + sideW / 2f, WallH / 2f, RoomFrontZ + WallT / 2f), new Vector3(sideW, WallH, WallT), wall, true);
            Box("Wall_Front_Top", new Vector3(0, (WallH + doorH) / 2f, RoomFrontZ + WallT / 2f), new Vector3(doorW, WallH - doorH, WallT), wall, true);
            // 문 안쪽을 막는 검은 판 — 열린 문 너머가 허공으로 보이지 않게 (텔레포트 포털이라 실제로 나가는 문은 아니다)
            Box("Door_Backdrop", new Vector3(0, doorH / 2f, RoomFrontZ + WallT * 1.6f), new Vector3(doorW, doorH, 0.02f * M), doorMat, false);

            // 문틀(어두운 트림) + 발광 EXIT 사인 + 바닥 매트
            float frameT = 0.15f * M;
            var frameL = Box("DoorFrame_L", new Vector3(-doorW / 2f - frameT / 2f, doorH / 2f, RoomFrontZ), new Vector3(frameT, doorH, frameT * 2f), trim, true, false);
            Box("DoorFrame_R", new Vector3(doorW / 2f + frameT / 2f, doorH / 2f, RoomFrontZ), new Vector3(frameT, doorH, frameT * 2f), trim, true, false);
            Box("DoorFrame_Top", new Vector3(0, doorH + frameT / 2f, RoomFrontZ), new Vector3(doorW + frameT * 2f, frameT, frameT * 2f), trim, true, false);
            var mat = Box("DoorMat", new Vector3(0, 0.01f * M, RoomFrontZ - 0.7f * M), new Vector3(doorW + frameT * 2f, 0.02f * M, 1.2f * M), doorMat, false, false);
            var signBoard = Box("ExitSignBoard", new Vector3(0, doorH + frameT + 0.32f * M, RoomFrontZ - 0.05f * M), new Vector3(1.6f * M, 0.42f * M, 0.06f * M), exitSign, false, false);
            // TextMesh 는 카메라가 +z 방향을 볼 때(글자의 -z 쪽에서) 바로 읽힌다. 출구 사인은 방 안(작은 z)에서 +z 를 보며 읽으니 회전 0,
            // 뒷벽 BOOTH 사인은 +z 쪽에서 -z 를 보며 읽으니 180° (2026-09-06 실측 — 반대로 두면 좌우 반전).
            // characterSize 실측(2026-09-06): 0.11·M 에서 글자 폭 2.9 m 로 판(1.6 m)을 넘쳤다 → 0.045·M ≈ 1.2 m.
            WorldText(room.transform, "ExitSignText", "EXIT  ·  축제로 나가기", new Vector3(0, doorH + frameT + 0.32f * M, RoomFrontZ - 0.09f * M),
                      Quaternion.identity, 0.045f * M, new Color(0.05f, 0.12f, 0.08f), font);

            // 부스 이름 — 셸 뒷벽(2.72 m) 위, 천장(3.4 m) 아래 띠에 맞춘다. 0.35·M 은 폭 11 m 였다 → 0.08·M ≈ 2.5 × 0.57 m.
            WorldText(room.transform, "Sign", $"BOOTH {id:D2}", new Vector3(0, 3.08f * M, RoomBackZ - 0.02f * M),
                      Quaternion.Euler(0f, 180f, 0f), 0.08f * M, new Color(0.25f, 0.2f, 0.15f), font);

            // ── 조명: 부스 위 4 등(따뜻함) + 통로 1 등. 천장 3.4 m 라 사거리 6 m 로 충분하다 ──
            var lampMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalLamp.mat");
            var lightPos = new[]
            {
                new Vector3(-1.6f * M, WallH - 0.2f * M, -1.6f * M), new Vector3(1.6f * M, WallH - 0.2f * M, -1.6f * M),
                new Vector3(-1.6f * M, WallH - 0.2f * M, 1.4f * M),  new Vector3(1.6f * M, WallH - 0.2f * M, 1.4f * M),
                new Vector3(0f, WallH - 0.2f * M, 4.6f * M),
            };
            for (int li = 0; li < lightPos.Length; li++)
            {
                var lgo = new GameObject(li == 4 ? "WalkwayLight" : $"BoothLight_{li}");
                lgo.transform.SetParent(room.transform, false);
                lgo.transform.localPosition = lightPos[li];
                var l = lgo.AddComponent<Light>();
                l.type = LightType.Point;
                l.color = new Color(1f, 0.95f, 0.88f);
                l.intensity = li == 4 ? 200f : 300f;   // 실측: 180 → 0.215, 340 → 0.235(카펫이 짙어 한계) — 카펫을 밝히고 300 으로
                l.range = 6.5f * M;
                l.shadows = LightShadows.None;

                if (lampMat != null)
                {
                    var strip = GameObject.CreatePrimitive(PrimitiveType.Quad);
                    Object.DestroyImmediate(strip.GetComponent<Collider>());
                    strip.name = $"CeilingLamp_{li}";
                    strip.transform.SetParent(room.transform, false);
                    strip.transform.localPosition = new Vector3(lightPos[li].x, WallH - 0.02f * M, lightPos[li].z);
                    strip.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);   // 아래를 본다
                    strip.transform.localScale = new Vector3(1.2f * M, 0.5f * M, 1f);
                    var sr = strip.GetComponent<Renderer>();
                    sr.sharedMaterial = lampMat;
                    sr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                }
            }

            // ── 부스 앵커: FE 레이아웃 미터 좌표의 원점 (1 m = 13.26 unit) ──
            var anchor = new GameObject($"BoothSlot_{id}");
            anchor.transform.SetParent(room.transform, false);
            anchor.transform.localPosition = Vector3.zero;
            anchor.transform.localScale = Vector3.one * AnchorScale;

            // ExpoKit 셸 = FE 계약과 같은 6 × 6 × 2.72 m. 트러스(철근)는 제외.
            var shell = (GameObject)PrefabUtility.InstantiatePrefab(shellPrefab);
            shell.name = "BoothShell";   // BoothRuntime 의 셸 자동 탐색 이름
            shell.transform.SetParent(anchor.transform, false);
            shell.transform.localPosition = Vector3.zero;
            // 셸 원본 방향 그대로: 벽이 -x(좌)·-z(후면), 정면 +z 개방 = FE 계약(passage.ts). 실측 2026-09-06 — 180° 돌리면 벽이 +x·+z 로 간다.
            shell.transform.localRotation = Quaternion.identity;
            shell.transform.localScale = Vector3.one;
            foreach (var t in shell.GetComponentsInChildren<Transform>(true).ToArray())
                if (t != null && t.name.StartsWith("Truss"))
                    Object.DestroyImmediate(t.gameObject);
            // 셸 카펫(Floor01)은 벤더 기본이 짙은 남색이라 방문객 시점 휘도가 0.2 대로 떨어진다(실측 2026-09-06).
            // 인스턴스 재질만 밝은 회청색 카펫으로 바꾼다 — 벤더 프리팹은 그대로.
            var carpet = Mat("InteriorBoothCarpet", new Color(0.52f, 0.55f, 0.64f), 0.05f);
            foreach (var r in shell.GetComponentsInChildren<Renderer>(true))
                if (r.gameObject.name.StartsWith("Floor"))
                {
                    var mats = r.sharedMaterials;
                    for (int mi = 0; mi < mats.Length; mi++) mats[mi] = carpet;
                    r.sharedMaterials = mats;
                }

            var runtimeType = System.AppDomain.CurrentDomain.GetAssemblies()
                .SelectMany(a => { try { return a.GetTypes(); } catch { return new System.Type[0]; } })
                .First(t => t.FullName == "Festa.Booth.BoothRuntime");
            var runtime = anchor.AddComponent(runtimeType);
            var so = new SerializedObject(runtime);
            so.FindProperty("_boothId").intValue = id;
            so.FindProperty("_registry").objectReferenceValue = registry;
            so.FindProperty("_loadOnStart").boolValue = false;   // WorldBoothPublishedBootstrap 이 게시본을 불러온다
            so.ApplyModifiedPropertiesWithoutUndo();

            // 스폰: 문 안쪽 통로, 부스를 바라본다(-z)
            var spawn = new GameObject("SpawnPoint");
            spawn.transform.SetParent(room.transform, false);
            // 셸 앞선에서 0.75 m 앞. 문 매트(5.1~6.3 m)까지 1.2 m 남아 입장 직후엔 "나가기" 프롬프트가 뜨지 않고, 한 걸음 물러서면 뜬다.
            spawn.transform.localPosition = new Vector3(0, 0.1f * M, 3.9f * M);
            spawn.transform.localRotation = Quaternion.Euler(0f, 180f, 0f);

            // 출구 포털 — 문 앞. 거리·하이라이트는 문틀 기준, 프롬프트는 "축제로 나가기". 목적지는 이름 규약(ReturnPoint_NN)으로 런타임 해결.
            var portalGo = new GameObject($"Portal_Int_{id:D2}");
            portalGo.transform.SetParent(room.transform, false);
            portalGo.transform.localPosition = new Vector3(0, 0, RoomFrontZ - 0.4f * M);
            var portal = portalGo.AddComponent<Festa.World.BoothPortal>();
            portal.boothId = id;
            var ret = GameObject.Find($"ReturnPoint_{id:D2}");
            portal.destination = ret != null ? ret.transform : null;
            portal.promptText = "축제로 나가기";
            portal.interactRadius = 1.5f * M;   // 매트 가장자리에서 1.5 m — 스폰 지점(1.2 m 앞)에서는 뜨지 않는다
            // 거리 기준은 바닥 매트 — 사인보드(높이 2.9 m)로 재면 3D 거리에 높이가 섞여 매트 위에 서도 39 unit 이 나온다(실측).
            portal.boundsSource = mat.GetComponent<Renderer>();
            _ = frameL; _ = signBoard;
        }

        static void WorldText(Transform parent, string name, string text, Vector3 localPos, Quaternion rot, float charSize, Color color, Font font)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = rot;
            var tm = go.AddComponent<TextMesh>();
            tm.text = text;
            tm.font = font;   // 런타임 TextMesh 폰트 명시 (T-158)
            tm.fontSize = 64;
            tm.characterSize = charSize;
            tm.anchor = TextAnchor.MiddleCenter;
            tm.color = color;
            var tr = go.GetComponent<MeshRenderer>();
            var signMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "InteriorSignText.mat");
            if (signMat == null)
            {
                signMat = new Material(Shader.Find("Festa/WorldText"));
                AssetDatabase.CreateAsset(signMat, MatDir + "InteriorSignText.mat");
            }
            signMat.mainTexture = font.material.mainTexture;
            EditorUtility.SetDirty(signMat);
            tr.sharedMaterial = signMat;
            tr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        static Material Mat(string name, Color c, float smooth)
        {
            var m = AssetDatabase.LoadAssetAtPath<Material>(MatDir + name + ".mat");
            if (m == null)
            {
                m = new Material(Shader.Find("Universal Render Pipeline/Lit"));
                AssetDatabase.CreateAsset(m, MatDir + name + ".mat");
            }
            m.color = c;
            m.SetFloat("_Smoothness", smooth);
            EditorUtility.SetDirty(m);
            return m;
        }

        static Material EmissiveMat(string name, Color c, float intensity)
        {
            var m = Mat(name, c, 0.2f);
            m.EnableKeyword("_EMISSION");
            m.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            m.SetColor("_EmissionColor", c * intensity);
            EditorUtility.SetDirty(m);
            return m;
        }
    }
}
