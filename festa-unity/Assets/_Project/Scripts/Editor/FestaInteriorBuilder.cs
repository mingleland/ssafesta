using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 내부 부스 공간 12실 생성기 v2. 외부 FestivalSlot_XX 와 번호(boothId 1~12)로 1:1 연결.
    ///
    /// - 방 32×32 m 밀폐 상자, 40 m 격자 (x 700~1900) — 서로 안 보인다.
    /// - 방마다 **BoothSlot_{id} 앵커 (스케일 20)** + ExpoKit BoothShell 기본 틀(트러스 제외)
    ///   + BoothRuntime — Published Layout 을 실제 통신 경로로 로드해 오브젝트를 Local Spawn 한다.
    ///
    /// 규격 계약 (FE·BE 와 공유):
    ///   레이아웃 좌표는 부스 로컬 **미터** 그대로다. 앵커 스케일 20 = "부스 로컬 1 m 를
    ///   월드 2 m 로" 그리는 표현 배율일 뿐이라, FE 스튜디오의 그리드·오브젝트 상대 규격은
    ///   기존(스케일 10, T-153)과 완전히 동일하게 유지된다. 데이터 계약 변경 없음.
    ///
    /// NetworkObject 없음 (Booth 정적 오브젝트 Local Spawn 원칙).
    /// </summary>
    public static class FestaInteriorBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string ShellPrefabPath = "Assets/_Project/Prefabs/Booth/BoothShell.prefab";
        const string RegistryPath = "Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset";
        const float RoomHalf = 160f;   // 바닥 반변 (32 m 방)
        const float WallH = 70f;       // 7 m — 2배 셸(5.4 m)이 여유 있게 들어간다
        const float Pitch = 400f;      // 방 간격 40 m
        const float AnchorScale = 20f; // 부스 로컬 1 m = 월드 2 m (기존 10 의 2배)

        [MenuItem("Festa/World/내부 부스 공간 재생성")]
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

            // 야외에 떠 있던 구 BoothSlot_7 은 제거한다 — 부스 런타임의 거처가 내부 공간으로
            // 옮겨졌고, 같은 boothId 런타임이 둘이면 콘텐츠·상호작용이 이중으로 생긴다.
            var legacy = GameObject.Find("/BoothSlot_7");
            if (legacy != null) { Object.DestroyImmediate(legacy); Debug.Log("[Interior] 야외 구 BoothSlot_7 제거 (내부로 이관)"); }

            var oldRoot = GameObject.Find("/@BoothInteriors");
            if (oldRoot != null) Object.DestroyImmediate(oldRoot);
            var root = new GameObject("@BoothInteriors");

            var wallMat = Mat("InteriorWall", new Color(0.78f, 0.76f, 0.72f), 0.1f);
            var floorMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "CorridorMid.mat");
            var padMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalLamp.mat");
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

            var extGroup = fest.transform.Find("Festival_Portals");
            if (extGroup != null) Object.DestroyImmediate(extGroup.gameObject);
            var ext = new GameObject("Festival_Portals");
            ext.transform.SetParent(fest.transform, false);

            int i = 0;
            foreach (Transform slot in slots)
            {
                i++;
                int col = (i - 1) % 4, row = (i - 1) / 4;
                var center = new Vector3(700f + col * Pitch, 0f, row * Pitch);
                var room = BuildRoom(root.transform, center, i, wallMat, floorMat, font, shellPrefab, registry);

                var front = slot.position.z > 145f ? Vector3.back : Vector3.forward;
                var doorPos = slot.position + front * 42f;

                var interiorSpawn = room.Find("SpawnPoint");
                MakePortal(ext.transform, $"Portal_Ext_{i:D2}", doorPos, i,
                           interiorSpawn, $"{i}번 부스 입장", 28f, padMat);

                var returnPoint = new GameObject($"ReturnPoint_{i:D2}");
                returnPoint.transform.SetParent(ext.transform, false);
                returnPoint.transform.position = doorPos + front * 12f + Vector3.up * 1f;
                var exitPos = center + new Vector3(0f, 0f, RoomHalf - 28f);
                MakePortal(room, $"Portal_Int_{i:D2}", exitPos, i,
                           returnPoint.transform, "축제로 나가기", 26f, padMat);
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(root.scene);
            Debug.Log($"[Interior] 방 {i}실(32 m) + 부스 앵커(스케일 {AnchorScale}) + 포털 {i * 2}개 — 씬 저장 필요");
        }

        static Transform BuildRoom(Transform parent, Vector3 c, int id, Material wall, Material floor,
                                   Font font, GameObject shellPrefab, ScriptableObject registry)
        {
            var room = new GameObject($"Interior_{id:D2}");
            room.transform.SetParent(parent, false);
            room.transform.position = c;

            void Box(string name, Vector3 pos, Vector3 size, Material m, bool castShadow)
            {
                var b = GameObject.CreatePrimitive(PrimitiveType.Cube);
                b.name = name;
                b.transform.SetParent(room.transform, false);
                b.transform.localPosition = pos;
                b.transform.localScale = size;
                var r = b.GetComponent<Renderer>();
                r.sharedMaterial = m;
                r.shadowCastingMode = castShadow
                    ? UnityEngine.Rendering.ShadowCastingMode.On
                    : UnityEngine.Rendering.ShadowCastingMode.Off;
                GameObjectUtility.SetStaticEditorFlags(b, StaticEditorFlags.OccludeeStatic);   // 배칭 금지 (T-191)
            }
            float H = RoomHalf;
            Box("Floor", new Vector3(0, -3f, 0), new Vector3(H * 2, 6, H * 2), floor, false);
            Box("Wall_N", new Vector3(0, WallH / 2, H), new Vector3(H * 2, WallH, 6), wall, true);
            Box("Wall_S", new Vector3(0, WallH / 2, -H), new Vector3(H * 2, WallH, 6), wall, true);
            Box("Wall_E", new Vector3(H, WallH / 2, 0), new Vector3(6, WallH, H * 2), wall, true);
            Box("Wall_W", new Vector3(-H, WallH / 2, 0), new Vector3(6, WallH, H * 2), wall, true);
            Box("Ceiling", new Vector3(0, WallH + 3f, 0), new Vector3(H * 2, 6, H * 2), wall, true);

            var light = new GameObject("RoomLight");
            light.transform.SetParent(room.transform, false);
            light.transform.localPosition = new Vector3(0, WallH - 8f, 0);
            var l = light.AddComponent<Light>();
            l.type = LightType.Point;
            l.color = new Color(1f, 0.93f, 0.82f);
            l.intensity = 520f;
            l.range = 420f;
            l.shadows = LightShadows.None;

            var signGo = new GameObject("Sign");
            signGo.transform.SetParent(room.transform, false);
            signGo.transform.localPosition = new Vector3(0, WallH - 20f, RoomHalf - 5f);
            var tm = signGo.AddComponent<TextMesh>();
            tm.text = $"BOOTH {id:D2}";
            tm.font = font;   // 런타임 TextMesh 폰트 명시 (T-158)
            tm.fontSize = 64;
            tm.characterSize = 2.4f;
            tm.anchor = TextAnchor.MiddleCenter;
            tm.color = new Color(0.25f, 0.2f, 0.15f);
            var tr = signGo.GetComponent<MeshRenderer>();
            tr.sharedMaterial = font.material;
            tr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;

            // ── 부스 앵커: FE 레이아웃 미터 좌표의 원점. 스케일 20 = 2배 표현 ──
            var anchor = new GameObject($"BoothSlot_{id}");
            anchor.transform.SetParent(room.transform, false);
            anchor.transform.localPosition = Vector3.zero;
            anchor.transform.localScale = Vector3.one * AnchorScale;

            // ExpoKit 셸 = 수정 가능한 기본 틀. 트러스(철근)는 제외 — 적용 까다로움.
            var shell = (GameObject)PrefabUtility.InstantiatePrefab(shellPrefab);
            shell.name = "BoothShell";   // BoothRuntime 의 셸 자동 탐색 이름
            shell.transform.SetParent(anchor.transform, false);
            shell.transform.localPosition = Vector3.zero;
            shell.transform.localScale = Vector3.one;
            foreach (var t in shell.GetComponentsInChildren<Transform>(true).ToArray())
                if (t != null && t.name.StartsWith("Truss"))
                    Object.DestroyImmediate(t.gameObject);

            // BoothRuntime — 실제 통신 경로(ApiServices→클라이언트→파서→팩토리)로
            // Published Layout 을 로드한다. Mock 모드에서는 모든 번호가 내장 레이아웃을,
            // Spring 전환 시에는 번호별 실데이터를 받는다.
            var runtimeType = System.AppDomain.CurrentDomain.GetAssemblies()
                .SelectMany(a => { try { return a.GetTypes(); } catch { return new System.Type[0]; } })
                .First(t => t.FullName == "Festa.Booth.BoothRuntime");
            var runtime = anchor.AddComponent(runtimeType);
            var so = new SerializedObject(runtime);
            so.FindProperty("_boothId").intValue = id;
            so.FindProperty("_registry").objectReferenceValue = registry;
            so.FindProperty("_loadOnStart").boolValue = true;
            so.ApplyModifiedPropertiesWithoutUndo();

            var spawn = new GameObject("SpawnPoint");
            spawn.transform.SetParent(room.transform, false);
            spawn.transform.localPosition = new Vector3(0, 1.2f, -(RoomHalf - 40f));
            return room.transform;
        }

        static void MakePortal(Transform parent, string name, Vector3 worldPos, int id,
                               Transform dest, string prompt, float radius, Material padMat)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.position = worldPos;
            var portal = go.AddComponent<Festa.World.BoothPortal>();
            portal.boothId = id;
            portal.destination = dest;
            portal.promptText = prompt;
            portal.interactRadius = radius;

            var pad = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            Object.DestroyImmediate(pad.GetComponent<Collider>());
            pad.name = "Pad";
            pad.transform.SetParent(go.transform, false);
            pad.transform.localPosition = new Vector3(0f, 0.35f, 0f);
            pad.transform.localScale = new Vector3(11f, 0.3f, 11f);
            var r = pad.GetComponent<Renderer>();
            r.sharedMaterial = padMat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
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
    }
}
