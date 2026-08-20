using System.Collections.Generic;
using System.Linq;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;

namespace Festa.World.EditorTools
{
    /// <summary>
    /// 11층 월드 모델을 새로 받았을 때 조명 셋업을 다시 붙이는 도구.
    ///
    /// 모델을 교체하면 프리팹 인스턴스가 새로 생기므로 인스턴스에 걸려 있던
    /// 머티리얼 슬롯(109건)·MeshCollider(4개)·ceiling 정렬이 전부 날아간다.
    /// 이 값들은 손으로 찍은 게 아니라 규칙으로 고른 것이므로 재현할 수 있다.
    ///
    /// 반대로 아래는 모델과 별개라 재임포트가 건드리지 않는다 — 다시 만들지 않는다.
    ///   - WorldCeilingLights 아래 광원 (일반 씬 오브젝트)
    ///   - WorldCeiling*.mat 머티리얼 애셋
    ///   - RenderSettings(환경광·sun 제거), Mobile_RPAsset 한도
    ///   - BoothSlot_* 아래 부스 오브젝트
    /// 단 광원 '위치'는 다운라이트 기구 bounds 에서 계산하므로 여기서 다시 배치한다.
    ///
    /// 근거: docs/KHS/25_트러블슈팅.md T-156(콜라이더), T-157(천장 정렬),
    ///       T-159(조명면은 Unlit), T-160(1m=10unit 스케일에서 광원 세기).
    /// </summary>
    static class WorldCeilingSetup
    {
        const string WorldRootName = "@World_11F";
        const string RoomName = "ssafy-11th-room";
        const string CeilingName = "ceiling";
        const string LightsRootName = "WorldCeilingLights";
        const string TileNamePrefix = "ceiling-light-large-small";
        const string DownlightNamePrefix = "ceiling-light-small";

        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string StripMatPath = MatDir + "WorldCeilingLightStrip.mat";
        const string DownlightMatPath = MatDir + "WorldCeilingDownlight.mat";
        const string BlackMatPath = MatDir + "WorldCeilingBlack.mat";

        // 모델에 임베드된 원본 머티리얼 이름 — 이 이름으로 슬롯을 찾는다.
        const string SrcStripMat = "[Translucent Glass Gray]";
        const string SrcPlainMat = "_defaultMat";

        // 콜라이더는 걷는 면과 막는 면에만. 벽의 장식 자식(도어락 784k verts)에는 달지 않는다 (T-156).
        static readonly string[] ColliderTargets = { "floor", "wall-windowside", "wall-elevatorside" };

        // 1 m = 10 unit 월드다. 감쇠가 1/d² 이므로 1:1 스케일 감각의 세기는 무의미하다 (T-160).
        const float SpotIntensity = 420f;
        const float SpotRange = 120f;
        const float SpotAngle = 70f;
        const float SpotInnerAngle = 35f;
        const float FillIntensity = 260f;
        const float FillRange = 160f;

        [MenuItem("Festa/World/천장 조명 셋업 다시 적용", false, 100)]
        static void Reapply()
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null)
            {
                Debug.LogError($"[WorldCeilingSetup] '{WorldRootName}' 이 씬에 없다.");
                return;
            }

            var room = FindDeep(world.transform, RoomName);
            var ceiling = FindDeep(world.transform, CeilingName);
            if (room == null || ceiling == null)
            {
                Debug.LogError($"[WorldCeilingSetup] '{RoomName}' 또는 '{CeilingName}' 을 찾지 못했다. " +
                               "모델 구조가 바뀌었으면 이 도구의 이름 상수를 먼저 맞춰라.");
                return;
            }

            var strip = Load<Material>(StripMatPath);
            var downlight = Load<Material>(DownlightMatPath);
            var black = Load<Material>(BlackMatPath);
            if (strip == null || downlight == null || black == null) return;

            int colliders = ApplyColliders(room);
            var (nStrip, nDown, nBlack) = ApplyCeilingMaterials(ceiling, strip, downlight, black);
            var (nSpot, nFill) = RebuildLights(world.transform, ceiling);
            ApplyAmbient();

            EditorSceneManager.MarkSceneDirty(world.scene);

            Debug.Log(
                "[WorldCeilingSetup] 재적용 완료\n" +
                $"  MeshCollider      : {colliders}개\n" +
                $"  라인조명 슬롯      : {nStrip}\n" +
                $"  다운라이트 슬롯    : {nDown}\n" +
                $"  천장배경 슬롯      : {nBlack}\n" +
                $"  Spot / Fill 광원   : {nSpot} / {nFill}\n" +
                "  천장 높이는 건드리지 않았다 — 필요하면 '천장을 벽 상단에 맞춤' 을 따로 실행한다.\n" +
                "  씬은 저장하지 않았다 — 검증 후 직접 저장한다.");

            Verify();
        }

        /// <summary>
        /// 천장 정렬은 재적용에서 분리해 뒀다. 층고를 의도적으로 조정해 둔 경우
        /// 재적용이 그 값을 덮어쓰면 안 되기 때문이다.
        /// </summary>
        [MenuItem("Festa/World/천장을 벽 상단에 맞춤", false, 102)]
        static void AlignCeilingMenu()
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null) { Debug.LogError($"[WorldCeilingSetup] '{WorldRootName}' 없음"); return; }
            var room = FindDeep(world.transform, RoomName);
            var ceiling = FindDeep(world.transform, CeilingName);
            if (room == null || ceiling == null) { Debug.LogError("[WorldCeilingSetup] 방/천장 없음"); return; }

            float before = ceiling.localPosition.y;
            float error = AlignCeiling(room, ceiling);
            EditorSceneManager.MarkSceneDirty(world.scene);
            Debug.Log($"[WorldCeilingSetup] 천장 정렬: localPosition.y {before:F4} → {ceiling.localPosition.y:F4} " +
                      $"(벽 상단 대비 오차 {error:F4} world unit)");
        }

        [MenuItem("Festa/World/셋업 검증 (읽기 전용)", false, 101)]
        static void Verify()
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null) { Debug.LogError($"[WorldCeilingSetup] '{WorldRootName}' 없음"); return; }

            var room = FindDeep(world.transform, RoomName);
            var ceiling = FindDeep(world.transform, CeilingName);
            var sb = new System.Text.StringBuilder("[WorldCeilingSetup] 검증\n");

            if (room != null)
            {
                var floor = room.Find("floor");
                if (floor != null)
                {
                    var fb = Bounds(floor);
                    sb.AppendLine($"  바닥 상단 y = {fb.max.y:F3}");
                }
                var wall = room.Find("wall-elevatorside");
                if (wall != null && ceiling != null)
                {
                    float diff = Bounds(ceiling).min.y - Bounds(wall).max.y;
                    sb.AppendLine($"  천장 하단 - 벽 상단 = {diff:F3} world unit ({diff / 10f:F3} m)");
                }
                sb.AppendLine($"  MeshCollider = {room.GetComponentsInChildren<MeshCollider>(true).Length}개");
            }

            // 스폰 지점에 바닥이 받쳐주는지 — 콜라이더가 조용히 깨지면 여기서 잡힌다 (T-156).
            var center = new Vector3(-75f, 0f, -235f);
            int hit = 0;
            float minY = float.MaxValue, maxY = float.MinValue;
            for (int i = 0; i < 40; i++)
            {
                var p = center + new Vector3((i % 8 - 3.5f) * 2.25f, 0f, (i / 8 - 2f) * 2.25f);
                if (Physics.Raycast(p + Vector3.up * 60f, Vector3.down, out var h, 200f))
                {
                    hit++;
                    minY = Mathf.Min(minY, h.point.y);
                    maxY = Mathf.Max(maxY, h.point.y);
                }
            }
            sb.AppendLine(hit == 40
                ? $"  스폰 바닥 40/40  y {minY:F3}~{maxY:F3}"
                : $"  스폰 바닥 {hit}/40  ← 콜라이더 확인 필요");

            var lights = world.transform.Find(LightsRootName);
            int spots = 0, points = 0;
            if (lights != null)
                foreach (var l in lights.GetComponentsInChildren<Light>(true))
                {
                    if (l.type == LightType.Spot) spots++;
                    else if (l.type == LightType.Point) points++;
                }
            sb.AppendLine($"  광원 Spot {spots} / Point {points}");

            int dirs = Object.FindObjectsByType<Light>(FindObjectsSortMode.None)
                .Count(l => l.type == LightType.Directional && l.enabled);
            sb.AppendLine($"  활성 Directional {dirs}개 (실내 씬이므로 0 이어야 한다)");
            sb.AppendLine($"  RenderSettings.sun = {(RenderSettings.sun ? RenderSettings.sun.name : "null")}");
            sb.AppendLine($"  ambient {RenderSettings.ambientMode} {RenderSettings.ambientLight}");

            Debug.Log(sb.ToString());
        }

        // ---------- 단계별 ----------

        /// <summary>걷는 면·막는 면에만 자기 메시로 콜라이더를 단다. 자식 장식은 제외 (T-156).</summary>
        static int ApplyColliders(Transform room)
        {
            int n = 0;
            foreach (var name in ColliderTargets)
            {
                var t = room.Find(name);
                if (t == null) { Debug.LogWarning($"[WorldCeilingSetup] 콜라이더 대상 '{name}' 없음"); continue; }
                var mf = t.GetComponent<MeshFilter>();
                if (mf == null || mf.sharedMesh == null) continue;
                var mc = t.GetComponent<MeshCollider>() ?? t.gameObject.AddComponent<MeshCollider>();
                mc.sharedMesh = mf.sharedMesh;
                mc.convex = false;
                n++;
            }
            // 방 노드 자체 메시(내부 구조물)도 막아준다.
            var ownMf = room.GetComponent<MeshFilter>();
            if (ownMf != null && ownMf.sharedMesh != null)
            {
                var mc = room.GetComponent<MeshCollider>() ?? room.gameObject.AddComponent<MeshCollider>();
                mc.sharedMesh = ownMf.sharedMesh;
                mc.convex = false;
                n++;
            }
            return n;
        }

        /// <summary>
        /// 천장 재질 배정. 순서가 중요하다 — 다운라이트 렌즈를 먼저 바꿔야
        /// 남은 _defaultMat 만 배경으로 검게 칠할 수 있다.
        /// 임베드 머티리얼(black 628슬롯, _defaultMat 89슬롯)은 천장 밖에서도 쓰이므로
        /// 리맵하지 않고 천장 아래 슬롯만 교체한다.
        /// </summary>
        static (int, int, int) ApplyCeilingMaterials(Transform ceiling, Material strip, Material downlight, Material black)
        {
            int nStrip = 0, nDown = 0, nBlack = 0;
            foreach (var r in ceiling.GetComponentsInChildren<Renderer>(true))
            {
                var mats = r.sharedMaterials;
                bool changed = false;
                for (int i = 0; i < mats.Length; i++)
                {
                    if (mats[i] == null) continue;
                    string n = mats[i].name;
                    if (n == SrcStripMat || n == strip.name) { mats[i] = strip; nStrip++; changed = true; }
                    else if (r.name.StartsWith(DownlightNamePrefix) && (n == SrcPlainMat || n == downlight.name))
                    { mats[i] = downlight; nDown++; changed = true; }
                    else if (n == SrcPlainMat || n == black.name) { mats[i] = black; nBlack++; changed = true; }
                }
                if (changed) r.sharedMaterials = mats;
            }
            return (nStrip, nDown, nBlack);
        }

        /// <summary>천장을 벽 상단에 앉힌다. 원본은 벽 위 공중에 떠 있다 (T-157).</summary>
        static float AlignCeiling(Transform room, Transform ceiling)
        {
            var wall = room.Find("wall-elevatorside");
            if (wall == null) return float.NaN;
            float wallTop = Bounds(wall).max.y;
            float scaleY = ceiling.lossyScale.y;
            if (Mathf.Approximately(scaleY, 0f)) return float.NaN;

            float delta = wallTop - Bounds(ceiling).min.y;
            ceiling.localPosition += new Vector3(0f, delta / scaleY, 0f);
            return Bounds(ceiling).min.y - wallTop;
        }

        /// <summary>
        /// 기구 위치에서 광원을 다시 배치한다. 발광 머티리얼은 GI 없이 실제로 비추지
        /// 않으므로 실광원이 필요하다. 그림자는 끈다 — 99개 그림자는 WebGL 에서 과하다.
        /// </summary>
        static (int, int) RebuildLights(Transform worldRoot, Transform ceiling)
        {
            var existing = worldRoot.Find(LightsRootName);
            if (existing != null) Object.DestroyImmediate(existing.gameObject);

            var root = new GameObject(LightsRootName).transform;
            root.SetParent(worldRoot, false);

            var spots = new GameObject("Downlights").transform;
            spots.SetParent(root, false);
            int nSpot = 0;
            foreach (var r in ceiling.GetComponentsInChildren<Renderer>(true))
            {
                if (!r.name.StartsWith(DownlightNamePrefix)) continue;
                var b = r.bounds;
                var go = new GameObject($"Spot_{++nSpot:000}");
                go.transform.SetParent(spots, false);
                go.transform.position = new Vector3(b.center.x, b.min.y + 0.5f, b.center.z);
                go.transform.rotation = Quaternion.Euler(90f, 0f, 0f); // 정면(+Z)을 아래로
                var li = go.AddComponent<Light>();
                li.type = LightType.Spot;
                li.color = new Color(1f, 0.96f, 0.90f);
                li.intensity = SpotIntensity;
                li.range = SpotRange;
                li.spotAngle = SpotAngle;
                li.innerSpotAngle = SpotInnerAngle;
                li.shadows = LightShadows.None;
            }

            var fills = new GameObject("StripFill").transform;
            fills.SetParent(root, false);
            int nFill = 0;
            foreach (Transform tile in ceiling)
            {
                if (!tile.name.StartsWith(TileNamePrefix)) continue;
                var tb = Bounds(tile);
                var go = new GameObject($"Fill_{++nFill:00}");
                go.transform.SetParent(fills, false);
                go.transform.position = new Vector3(tb.center.x, tb.min.y - 1f, tb.center.z);
                var li = go.AddComponent<Light>();
                li.type = LightType.Point;
                li.color = new Color(1f, 0.98f, 0.95f);
                li.intensity = FillIntensity;
                li.range = FillRange;
                li.shadows = LightShadows.None;
            }
            return (nSpot, nFill);
        }

        /// <summary>실내 씬이라 햇빛을 쓰지 않는다. 스카이박스 대신 평면 색으로 통제 (T-158).</summary>
        static void ApplyAmbient()
        {
            RenderSettings.sun = null;
            RenderSettings.ambientMode = AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.20f, 0.21f, 0.24f, 1f);
            RenderSettings.ambientIntensity = 1f;
        }

        // ---------- 유틸 ----------

        static Transform FindDeep(Transform root, string name) =>
            root.GetComponentsInChildren<Transform>(true).FirstOrDefault(t => t.name == name);

        static Bounds Bounds(Transform t)
        {
            var rs = t.GetComponentsInChildren<Renderer>(true);
            if (rs.Length == 0) return new Bounds(t.position, Vector3.zero);
            var b = rs[0].bounds;
            for (int i = 1; i < rs.Length; i++) b.Encapsulate(rs[i].bounds);
            return b;
        }

        static T Load<T>(string path) where T : Object
        {
            var a = AssetDatabase.LoadAssetAtPath<T>(path);
            if (a == null) Debug.LogError($"[WorldCeilingSetup] 애셋 없음: {path}");
            return a;
        }
    }
}
