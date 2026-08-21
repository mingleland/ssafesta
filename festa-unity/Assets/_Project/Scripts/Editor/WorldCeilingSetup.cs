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

        // ── 콜리전 구성 ────────────────────────────────────────────
        // 형태에 맞는 콜라이더를 쓴다. 전부 박스로 덮으면 기둥 모서리에 걸리고,
        // 전부 메시로 덮으면 장식 지오메트리(벽 하나가 784k verts)까지 물려 비싸진다.
        //
        // 메시: 자기 메시가 정확하고 정점이 적은 것. 개구부가 있으면 박스로는 막혀버린다.
        // 박스: 실제로 직육면체인 것. 끝벽 16장처럼 한 평면에 늘어선 것은 하나로 묶는다.
        // 캡슐: 기둥·사람 형태. 박스로 하면 모서리에 걸려 이동이 끊긴다.

        /// <summary>자기 메시로 MeshCollider — 정점이 적고 형태가 정확해야 하는 면.</summary>
        static readonly string[] MeshColliderTargets =
        {
            "floor",             //     78 verts — 걷는 면
            "wall-windowside",   //    152 verts — 창측 벽
            "wall-elevatorside", //    776 verts — 엘리베이터측 벽 (자식 도어락 784k 는 제외)
            "Group 50",          //    140 verts — 내부 구조물. 개구부가 있어 박스로 막으면 못 지나간다
            "Group 116",         //     96 verts — 슬래브형 구조물
        };

        /// <summary>BoxCollider — 실제로 직육면체인 것 (소파·선반·벽면판).</summary>
        static readonly string[] BoxColliderTargets =
        {
            "Group 46", "Group 48", "Group 106", "Group 108", "Group 118", // 소파
            "Group 52",  // 벽 선반 (하단 0.93 m — 허리 높이라 막아야 한다)
            "Group 54",  // 벽면 사이니지 패널
        };

        /// <summary>CapsuleCollider — 기둥·사람 형태. 모서리 걸림을 피한다.</summary>
        static readonly string[] CapsuleColliderTargets = { "cylinder", "cylinder 1", "Niraj" };

        /// <summary>
        /// 끝벽. 한 평면에 21.7 m 패널이 늘어서 있어 그룹별로 콜라이더를 달면 16개가 된다.
        /// 완전한 직선이므로 라인별로 BoxCollider 하나로 묶는다 (16 → 2).
        /// </summary>
        static readonly string[][] EndWallGroups =
        {
            new[] { "Group 4", "Group 9", "Group 16" },   // 후면 z≈-333
            new[] { "Group 23", "Group 28", "Group 35" }, // 전면 z≈-120
        };

        const float EndWallMinThickness = 3f; // 실측 0.26 — 얇으면 빠른 이동에서 통과하므로 두껍게 잡는다

        // ── 벽 구멍 막기 ──────────────────────────────────────────
        // 엘리베이터 문처럼 벽에 실제 개구부가 있으면 콜라이더를 다 붙여도 그 틈으로 나간다.
        // 포털 기능이 생기기 전까지는 막아 둔다.
        //
        // z 좌표를 박아 넣지 않는다 — 모델이 바뀌면 문 위치도 바뀐다.
        // 대신 플레이어 캡슐로 벽을 따라 훑어 '아무것도 맞지 않는 구간'을 찾아 그 구간만 막는다.
        // 막는 x 는 개구부 양옆 벽면에서 읽어 오므로 문 평면과 정확히 맞고 투명 벽이 생기지 않는다.
        const string SealRootName = "@WorldCollisionSeals";
        const float PlayerRadius = 2.2f;   // PlayerAvatar 콜라이더 실측
        const float PlayerHeight = 13.5f;
        const float SealStep = 1f;         // 훑는 간격(unit)
        const float SealThickness = 2f;
        const float SealMargin = 1f;       // 개구부 양끝을 조금 넘겨 덮는다
        const float SealHeight = 50f;      // 벽 높이(실측 49.5)에 맞춘다 — 점프가 생겨도 넘어가지 못하게

        // 1 m = 10 unit 월드다. 감쇠가 1/d² 이므로 1:1 스케일 감각의 세기는 무의미하다 (T-160).
        const float SpotIntensity = 420f;
        const float SpotRange = 120f;
        const float SpotAngle = 70f;
        const float SpotInnerAngle = 35f;
        const float FillIntensity = 260f;
        const float FillRange = 160f;

        // 살짝 웜톤. B 만 조금 내린다 — 더 내리면 노랗게 뜬다.
        // 환경광도 같은 방향이어야 한다. 쿨한 환경광은 광원의 웜톤을 상쇄해 버린다.
        static readonly Color SpotColor = new Color(1f, 0.945f, 0.855f);
        static readonly Color FillColor = new Color(1f, 0.955f, 0.885f);
        static readonly Color AmbientColor = new Color(0.215f, 0.208f, 0.198f, 1f);

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

            var (nMesh, nBox, nCapsule) = ApplyColliders(room, room.parent);
            // 콜라이더를 먼저 붙인 뒤에 훑어야 남은 구멍만 찾아낸다.
            int nSeal = SealWallGaps(world.transform, room);
            var (nStrip, nDown, nBlack) = ApplyCeilingMaterials(ceiling, strip, downlight, black);
            var (nSpot, nFill) = RebuildLights(world.transform, ceiling);
            ApplyAmbient();

            EditorSceneManager.MarkSceneDirty(world.scene);

            Debug.Log(
                "[WorldCeilingSetup] 재적용 완료\n" +
                $"  콜라이더 Mesh/Box/Capsule : {nMesh} / {nBox} / {nCapsule}\n" +
                $"  벽 개구부 봉쇄     : {nSeal}개\n" +
                $"  라인조명 슬롯      : {nStrip}\n" +
                $"  다운라이트 슬롯    : {nDown}\n" +
                $"  천장배경 슬롯      : {nBlack}\n" +
                $"  Spot / Fill 광원   : {nSpot} / {nFill}\n" +
                "  천장 높이는 건드리지 않았다 — 필요하면 '천장을 벽 상단에 맞춤' 을 따로 실행한다.\n" +
                "  씬은 저장하지 않았다 — 검증 후 직접 저장한다.");

            Verify();
        }

        /// <summary>
        /// 조명만 다시 적용한다 — 콜라이더와 벽 봉쇄는 건드리지 않는다.
        ///
        /// 모델을 교체하는 중에는 콜라이더 대상 이름이 아직 맞지 않는다. 그 상태로
        /// 전체 재적용을 돌리면 없는 이름은 조용히 건너뛰고 남은 개구부 탐색도
        /// 잘못된 벽 기준으로 돌아간다. 조명은 천장 기구 이름만 보므로 콜라이더
        /// 대상을 정리하기 전에 먼저 맞출 수 있다.
        /// </summary>
        [MenuItem("Festa/World/조명만 다시 적용 (콜라이더 제외)", false, 103)]
        static void ReapplyLightsOnly()
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null) { Debug.LogError($"[WorldCeilingSetup] '{WorldRootName}' 이 씬에 없다."); return; }

            var ceiling = FindDeep(world.transform, CeilingName);
            if (ceiling == null)
            {
                Debug.LogError($"[WorldCeilingSetup] '{CeilingName}' 을 찾지 못했다. " +
                               "모델 구조가 바뀌었으면 이 도구의 이름 상수를 먼저 맞춰라.");
                return;
            }

            var strip = Load<Material>(StripMatPath);
            var downlight = Load<Material>(DownlightMatPath);
            var black = Load<Material>(BlackMatPath);
            if (strip == null || downlight == null || black == null) return;

            var (nStrip, nDown, nBlack) = ApplyCeilingMaterials(ceiling, strip, downlight, black);
            var (nSpot, nFill) = RebuildLights(world.transform, ceiling);
            ApplyAmbient();

            EditorSceneManager.MarkSceneDirty(world.scene);

            Debug.Log(
                "[WorldCeilingSetup] 조명만 재적용 완료 — 콜라이더·봉쇄는 건드리지 않았다\n" +
                $"  라인조명 슬롯   : {nStrip}\n" +
                $"  다운라이트 슬롯 : {nDown}\n" +
                $"  천장배경 슬롯   : {nBlack}\n" +
                $"  Spot / Fill     : {nSpot} / {nFill}\n" +
                "  천장 높이는 건드리지 않았다 — 필요하면 '천장을 벽 상단에 맞춤' 을 따로 실행한다.\n" +
                "  씬은 저장하지 않았다 — 검증 후 직접 저장한다.");
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

        /// <summary>
        /// 월드 콜리전을 구성한다. 형태별로 다른 콜라이더를 쓰고, 장식 자식에는 달지 않는다 (T-156).
        /// 반환값은 (메시, 박스, 캡슐) 개수.
        /// </summary>
        static (int mesh, int box, int capsule) ApplyColliders(Transform room, Transform worldModel)
        {
            int nMesh = 0, nBox = 0, nCapsule = 0;

            // 1) 자기 메시로 정확히 막을 것
            foreach (var name in MeshColliderTargets)
            {
                var t = room.Find(name);
                if (t == null) { Debug.LogWarning($"[WorldCeilingSetup] 메시 콜라이더 대상 '{name}' 없음"); continue; }
                var mf = t.GetComponent<MeshFilter>();
                if (mf == null || mf.sharedMesh == null) continue;
                var mc = GetOrAdd<MeshCollider>(t);
                mc.sharedMesh = mf.sharedMesh;
                mc.convex = false; // 정적 지오메트리는 non-convex 가 정확하고 BVH 로 질의된다
                nMesh++;
            }

            // 방 노드 자체 메시(내부 구조물·SSAFY 글자 등)
            var ownMf = room.GetComponent<MeshFilter>();
            if (ownMf != null && ownMf.sharedMesh != null)
            {
                var mc = GetOrAdd<MeshCollider>(room);
                mc.sharedMesh = ownMf.sharedMesh;
                mc.convex = false;
                nMesh++;
            }

            // 2) 직육면체인 것
            foreach (var name in BoxColliderTargets)
            {
                var t = room.Find(name);
                if (t == null) { Debug.LogWarning($"[WorldCeilingSetup] 박스 콜라이더 대상 '{name}' 없음"); continue; }
                if (TryFitBox(t, 0f)) nBox++;
            }

            // 3) 기둥·사람 형태
            foreach (var name in CapsuleColliderTargets)
            {
                var t = room.Find(name);
                if (t == null) { Debug.LogWarning($"[WorldCeilingSetup] 캡슐 콜라이더 대상 '{name}' 없음"); continue; }
                if (TryFitCapsule(t)) nCapsule++;
            }

            // 4) 끝벽 — 라인별로 하나로 묶는다
            if (worldModel != null)
            {
                foreach (var group in EndWallGroups)
                {
                    var holder = worldModel.Find(group[0]);
                    if (holder == null) { Debug.LogWarning($"[WorldCeilingSetup] 끝벽 그룹 '{group[0]}' 없음"); continue; }

                    var union = new Bounds();
                    bool any = false;
                    foreach (var gname in group)
                    {
                        var g = worldModel.Find(gname);
                        if (g == null) continue;
                        var b = Bounds(g);
                        if (!any) { union = b; any = true; } else union.Encapsulate(b);
                    }
                    if (!any) continue;

                    // 첫 그룹 오브젝트에 union 크기의 BoxCollider 를 단다.
                    if (FitBoxToWorldBounds(holder, union, EndWallMinThickness)) nBox++;
                }
            }

            return (nMesh, nBox, nCapsule);
        }

        /// <summary>
        /// 벽을 훑어 실제 개구부(엘리베이터 문 등)를 찾아 막는다.
        /// 모델 좌표를 박아 넣지 않고 물리 질의로 찾으므로 모델이 바뀌어도 다시 맞는다.
        /// 생성물은 모델 밖의 독립 오브젝트라 모델 교체에 영향받지 않는다.
        /// </summary>
        static int SealWallGaps(Transform worldRoot, Transform room)
        {
            var existing = worldRoot.Find(SealRootName);
            if (existing != null) Object.DestroyImmediate(existing.gameObject);

            var floorT = room.Find("floor");
            if (floorT == null) return 0;
            var floor = Bounds(floorT);
            float floorTop = floor.max.y;

            var root = new GameObject(SealRootName).transform;
            root.SetParent(worldRoot, false);

            int sealed_ = 0;
            // 엘리베이터측(+x)·창측(-x) 두 벽을 각각 훑는다.
            sealed_ += SealAlongZ(root, floor, floorTop, +1f);
            sealed_ += SealAlongZ(root, floor, floorTop, -1f);

            if (root.childCount == 0) Object.DestroyImmediate(root.gameObject);
            return sealed_;
        }

        /// <summary>z 방향으로 훑으며 x축 벽의 개구부를 막는다. dirX 는 +1(엘리베이터측)/-1(창측).</summary>
        static int SealAlongZ(Transform sealRoot, Bounds floor, float floorTop, float dirX)
        {
            // 벽 바로 앞(실내 빈 공간)에서 벽을 향해 쏜다.
            // 방 중앙에서 쏘면 실내 구조물에 캡슐이 박혀 대부분의 z 가 판정 불가로 걸러진다.
            const float StandoffFromWall = 30f; // 3 m — 벽 앞 통로에 들어가는 거리
            float originX = dirX > 0f ? floor.max.x - StandoffFromWall : floor.min.x + StandoffFromWall;
            var dir = new Vector3(dirX, 0f, 0f);
            var lo = new Vector3(0f, floorTop + PlayerRadius + 0.2f, 0f);
            var hi = new Vector3(0f, floorTop + PlayerHeight - PlayerRadius, 0f);
            float castLen = StandoffFromWall * 4f; // 벽까지 닿을 만큼만 — 반대편 벽까지 쏘면 구멍을 못 본다

            var gaps = new List<float>();
            for (float z = floor.min.z; z <= floor.max.z; z += SealStep)
            {
                var s = new Vector3(originX, 0f, z);
                // 출발점이 이미 무언가 안에 있으면 판정할 수 없다.
                if (Physics.CheckCapsule(s + lo, s + hi, PlayerRadius, ~0, QueryTriggerInteraction.Ignore)) continue;
                if (!Physics.CapsuleCast(s + lo, s + hi, PlayerRadius, dir, out _, castLen, ~0, QueryTriggerInteraction.Ignore))
                    gaps.Add(z);
            }
            if (gaps.Count == 0) return 0;

            // 연속 구간으로 묶는다.
            var ranges = new List<(float a, float b)>();
            float start = gaps[0], prev = gaps[0];
            for (var i = 1; i < gaps.Count; i++)
            {
                if (gaps[i] - prev > SealStep * 1.5f) { ranges.Add((start, prev)); start = gaps[i]; }
                prev = gaps[i];
            }
            ranges.Add((start, prev));

            int made = 0;
            foreach (var r in ranges)
            {
                // 개구부 양옆 벽면 x 를 읽어 그 평면에 맞춘다.
                float wallX = float.NaN;
                foreach (var probeZ in new[] { r.a - SealStep * 3f, r.b + SealStep * 3f })
                {
                    var s = new Vector3(originX, 0f, probeZ);
                    if (Physics.CheckCapsule(s + lo, s + hi, PlayerRadius, ~0, QueryTriggerInteraction.Ignore)) continue;
                    if (Physics.CapsuleCast(s + lo, s + hi, PlayerRadius, dir, out var h, castLen, ~0, QueryTriggerInteraction.Ignore))
                    {
                        wallX = originX + dirX * (h.distance + PlayerRadius);
                        break;
                    }
                }
                if (float.IsNaN(wallX)) continue; // 양옆도 뚫려 있으면 개구부가 아니라 벽 자체가 없는 것

                var go = new GameObject($"Seal_{(dirX > 0f ? "East" : "West")}_{++made:00}");
                go.transform.SetParent(sealRoot, false);
                float zCenter = (r.a + r.b) * 0.5f;
                float zSize = (r.b - r.a) + SealStep + SealMargin * 2f;
                // 높이는 플레이어 키가 아니라 벽 높이에 맞춘다 — 점프가 들어가도 넘어가지 못하게.
                go.transform.position = new Vector3(wallX + dirX * (SealThickness * 0.5f),
                                                    floorTop + SealHeight * 0.5f, zCenter);
                var bc = go.AddComponent<BoxCollider>();
                bc.size = new Vector3(SealThickness, SealHeight, zSize);
                // 렌더러가 없으므로 보이지 않는다 — 물리 전용.
            }
            return made;
        }

        /// <summary>렌더러 바운즈에 맞춘 BoxCollider. minThickness 는 얇은 벽의 통과를 막는 최소 두께.</summary>
        static bool TryFitBox(Transform t, float minThickness) =>
            FitBoxToWorldBounds(t, Bounds(t), minThickness);

        static bool FitBoxToWorldBounds(Transform t, Bounds world, float minThickness)
        {
            if (world.size == Vector3.zero) return false;
            var bc = GetOrAdd<BoxCollider>(t);

            // world → 로컬. 회전이 90° 배수라 축 맞교환만 일어나므로 절대값으로 환산한다.
            var localCenter = t.InverseTransformPoint(world.center);
            var lossy = t.lossyScale;
            var size = t.InverseTransformVector(world.size);
            size = new Vector3(Mathf.Abs(size.x), Mathf.Abs(size.y), Mathf.Abs(size.z));

            if (minThickness > 0f)
            {
                // 가장 얇은 축을 최소 두께로 늘린다 (로컬 스케일 반영).
                var scaled = new Vector3(size.x * Mathf.Abs(lossy.x), size.y * Mathf.Abs(lossy.y), size.z * Mathf.Abs(lossy.z));
                int thin = scaled.x <= scaled.y && scaled.x <= scaled.z ? 0 : (scaled.y <= scaled.z ? 1 : 2);
                float need = minThickness / Mathf.Max(0.0001f, Mathf.Abs(lossy[thin]));
                if (size[thin] < need) size[thin] = need;
            }

            bc.center = localCenter;
            bc.size = size;
            return true;
        }

        /// <summary>세로로 긴 형태에 맞춘 CapsuleCollider. 기둥·마네킹처럼 모서리 걸림을 피할 대상에 쓴다.</summary>
        static bool TryFitCapsule(Transform t)
        {
            var world = Bounds(t);
            if (world.size == Vector3.zero) return false;
            var cc = GetOrAdd<CapsuleCollider>(t);

            var localCenter = t.InverseTransformPoint(world.center);
            var size = t.InverseTransformVector(world.size);
            size = new Vector3(Mathf.Abs(size.x), Mathf.Abs(size.y), Mathf.Abs(size.z));

            // 가장 긴 로컬 축을 캡슐 축으로 잡는다 (모델 회전이 축을 바꿔놓기 때문).
            int axis = size.x >= size.y && size.x >= size.z ? 0 : (size.y >= size.z ? 1 : 2);
            int a = (axis + 1) % 3, b = (axis + 2) % 3;

            cc.center = localCenter;
            cc.direction = axis;
            cc.height = size[axis];
            cc.radius = Mathf.Max(size[a], size[b]) * 0.5f;
            return true;
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

        /// <summary>
        /// 천장을 벽 상단에 앉힌다. 원본은 벽 위 공중에 떠 있다 (T-157).
        ///
        /// 나눗셈에 **부모의** lossyScale 을 쓴다 — `localPosition` 은 부모 공간이라
        /// 월드 거리를 부모 스케일로 나눠야 한다. 자기 lossyScale 로 나누면 자신의
        /// localScale 만큼 어긋난다. 이 천장은 localScale 0.8, 부모 12.5 라
        /// lossyScale 은 10 이고, 10 으로 나누면 25% 과다 이동한다 (T-172).
        /// 매 회 오차가 ¼로 줄어 여러 번 돌리면 수렴하기 때문에 오래 안 드러났다.
        /// </summary>
        static float AlignCeiling(Transform room, Transform ceiling)
        {
            var wall = room.Find("wall-elevatorside");
            if (wall == null) return float.NaN;
            float wallTop = Bounds(wall).max.y;
            float scaleY = ceiling.parent != null ? ceiling.parent.lossyScale.y : 1f;
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
                li.color = SpotColor;
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
                li.color = FillColor;
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
            RenderSettings.ambientLight = AmbientColor;
            RenderSettings.ambientIntensity = 1f;
        }

        // ---------- 유틸 ----------

        static Transform FindDeep(Transform root, string name) =>
            root.GetComponentsInChildren<Transform>(true).FirstOrDefault(t => t.name == name);

        /// <summary>
        /// 컴포넌트를 가져오거나 없으면 추가한다.
        ///
        /// `GetComponent<T>() ?? AddComponent<T>()` 를 쓰면 안 된다. Unity 의 GetComponent 는
        /// 컴포넌트가 없을 때 진짜 null 이 아니라 **네이티브 포인터가 비어 있는 래퍼**를 돌려준다.
        /// `==` 는 UnityEngine.Object 가 연산자를 재정의해 true 가 되지만 `??` 는 참조로만 보므로
        /// 단축평가되지 않는다. 그 래퍼에 값을 대입하면 MissingComponentException 이 난다.
        /// </summary>
        static T GetOrAdd<T>(Transform t) where T : Component
        {
            var c = t.GetComponent<T>();
            return c == null ? t.gameObject.AddComponent<T>() : c;
        }
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
