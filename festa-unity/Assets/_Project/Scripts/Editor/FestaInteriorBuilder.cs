using System.Linq;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 내부 부스 공간 12실 생성기. 외부 FestivalSlot_XX 와 번호(boothId 1~12)로 1:1 연결.
    ///
    /// - 방은 밀폐 상자(바닥·벽4·천장)로 40 m 간격 격자 배치 — 서로 안 보인다.
    /// - 출입은 BoothPortal 쌍: 외부 부스 앞 → 내부 스폰 / 내부 출구 패드 → 외부 복귀점.
    /// - NetworkObject 없음 (정적 로컬 오브젝트 원칙). 백엔드 연동 시 boothId 를 쓴다.
    /// - 위치는 플레이 가능 영역(x -1225~30)에서 멀리: x 700~1900, z 0~800.
    ///   카메라 far 2800 이라 축제에서 안 보이고, 방 안에서는 밀폐라 밖이 안 보인다.
    /// </summary>
    public static class FestaInteriorBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const float RoomHalf = 90f;    // 바닥 반변 (18 m 방)
        const float WallH = 50f;       // 5 m
        const float Pitch = 400f;      // 방 간격 40 m

        [MenuItem("Festa/World/내부 부스 공간 재생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Interior] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var fest = GameObject.Find("/@Festival");
            var slots = fest != null ? fest.transform.Find("Festival_Slots") : null;
            if (slots == null) { Debug.LogError("[Interior] Festival_Slots 없음"); return; }

            var oldRoot = GameObject.Find("/@BoothInteriors");
            if (oldRoot != null) Object.DestroyImmediate(oldRoot);
            var root = new GameObject("@BoothInteriors");

            var wallMat = Mat("InteriorWall", new Color(0.78f, 0.76f, 0.72f), 0.1f);
            var floorMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "CorridorMid.mat");   // 목재 재사용
            var padMat = AssetDatabase.LoadAssetAtPath<Material>(MatDir + "FestivalLamp.mat");    // 발광 패드 재사용
            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");                    // 런타임 TextMesh 폰트 명시 (내장 리소스)

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
                var room = BuildRoom(root.transform, center, i, wallMat, floorMat, font);

                // 부스 정면 = 거리(z 145) 쪽
                var front = slot.position.z > 145f ? Vector3.back : Vector3.forward;
                var doorPos = slot.position + front * 42f;

                // 외부 포털 (부스 앞) → 내부 스폰
                var interiorSpawn = room.Find("SpawnPoint");
                MakePortal(ext.transform, $"Portal_Ext_{i:D2}", doorPos, i,
                           interiorSpawn, $"{i}번 부스 입장", 28f, padMat);

                // 내부 출구 포털 → 외부 복귀점 (문 앞보다 거리 쪽으로 한 걸음)
                var returnPoint = new GameObject($"ReturnPoint_{i:D2}");
                returnPoint.transform.SetParent(ext.transform, false);
                returnPoint.transform.position = doorPos + front * 12f + Vector3.up * 1f;
                var exitPos = center + new Vector3(0f, 0f, 62f);
                MakePortal(room, $"Portal_Int_{i:D2}", exitPos, i,
                           returnPoint.transform, "축제로 나가기", 26f, padMat);
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(root.scene);
            Debug.Log($"[Interior] 방 {i}실 + 포털 {i * 2}개 생성 — 씬 저장 필요");
        }

        static Transform BuildRoom(Transform parent, Vector3 c, int id, Material wall, Material floor, Font font)
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
                // 천장·벽이 달빛을 막아야 방이 자체 조명만 받는다
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
            light.transform.localPosition = new Vector3(0, WallH - 6f, 0);
            var l = light.AddComponent<Light>();
            l.type = LightType.Point;
            l.color = new Color(1f, 0.93f, 0.82f);
            l.intensity = 420f;
            l.range = 260f;
            l.shadows = LightShadows.None;

            // 부스 번호 표지
            var signGo = new GameObject("Sign");
            signGo.transform.SetParent(room.transform, false);
            signGo.transform.localPosition = new Vector3(0, 32f, RoomHalf - 5f);
            var tm = signGo.AddComponent<TextMesh>();
            tm.text = $"BOOTH {id:D2}";
            tm.font = font;
            tm.fontSize = 64;
            tm.characterSize = 1.6f;
            tm.anchor = TextAnchor.MiddleCenter;
            tm.color = new Color(0.25f, 0.2f, 0.15f);
            var tr = signGo.GetComponent<MeshRenderer>();
            tr.sharedMaterial = font.material;
            tr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;

            var spawn = new GameObject("SpawnPoint");
            spawn.transform.SetParent(room.transform, false);
            spawn.transform.localPosition = new Vector3(0, 1.2f, -40f);
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

            // 상호작용 지점이 보이도록 발광 패드 (콜라이더 제거 — 통행 방해 금지)
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
