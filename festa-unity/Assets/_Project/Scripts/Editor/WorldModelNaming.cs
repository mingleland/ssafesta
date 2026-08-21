using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 월드 모델의 `Group N` 이름을 읽을 수 있는 이름으로 바꾸는 규칙.
    ///
    /// 이 클래스는 규칙만 담는다. 두 곳에서 쓴다:
    ///   1) <see cref="WorldModelPostprocessor"/> — **임포트 시점**에 애셋 자체를 고친다 (기본 경로)
    ///   2) <see cref="WorldHierarchyNaming"/> — 씬 인스턴스에 수동 적용 (미리보기·구제용)
    ///
    /// 왜 이름이 아니라 지오메트리로 찾는가 —
    /// `Group N` 의 N 은 SketchUp export 마다 바뀐다. 0820 → 0821 교체에서 끝벽이
    /// `Group 4·9·16 / 23·28·35` → `Group 3·8·15 / 22·27·34` 로 전부 밀렸다.
    /// 이름을 열거하면 다음 모델에서 또 깨지므로 위치·크기·머티리얼로 찾는다.
    ///
    /// bounds 는 항상 **MeshFilter 의 공유 메시를 직접 변환해서** 구한다.
    /// `Renderer.bounds` 는 임포트 중(씬에 없는 계층)에는 신뢰할 수 없다.
    /// </summary>
    public static class WorldModelNaming
    {
        public const string RoomName = "ssafy-11th-room";
        const string CeilingName = "ceiling";

        // 임계값은 모두 **방 크기에 대한 비율**이다. 절대값을 쓰면 안 된다 —
        // 이 규칙은 모델 로컬 공간에서 돌고, 로컬 단위는 씬 스케일(12.5)만큼 작다.
        // 처음에 월드 단위 상수(8, 2)를 그대로 두었다가 허용 오차가 100 world unit 이 되어
        // `mock-floor`·`mock-wall` 까지 끝벽으로 잡았다 (T-173).
        const float EndWallZToleranceRatio = 0.04f;  // 방 z 폭의 4% 안이면 끝단
        const float FlatRatio = 0.02f;               // 방 z 폭의 2% 보다 얇으면 평면
        const float WallHeightRatio = 0.5f;          // 방 높이의 절반 이상이어야 벽 (마커 배제)
        const float InRoomMargin = 0.1f;             // 방 x 범위 밖이면 벽이 아니다 (방 x 폭 비율)
        const string SeatMaterial = "ssafy-sofa";
        const string WoodFloorMaterial = "[Wood Floor]";

        /// <summary>스케일 참조 마커. 방 바깥에 렌더링되므로 임포트 시 끈다.</summary>
        static readonly string[] ScaleMarkers = { "mock-floor", "mock-wall" };

        /// <summary>이 계층이 11층 월드 모델인가.</summary>
        public static bool IsWorldModel(Transform root) => FindChild(root, RoomName) != null;

        /// <summary>
        /// 계획을 세운다. 실제 변경은 호출자가 한다 — 씬에서는 Undo 기록이 필요하고
        /// 임포트 중에는 필요하지 않기 때문이다.
        /// </summary>
        public static List<(Transform target, string newName, string reason)> BuildPlan(Transform model)
        {
            var plan = new List<(Transform, string, string)>();
            var room = FindChild(model, RoomName);
            if (room == null) return plan;

            PlanEndWalls(model, LocalBounds(model, room), plan);
            PlanSeats(room, model, plan);
            PlanLoungeZone(room, model, plan);
            PlanCeilingBackdrop(model, plan);
            return plan;
        }

        /// <summary>계획을 적용하고 사람이 읽을 보고서를 만든다.</summary>
        public static string Apply(Transform model, bool dryRun, System.Action<GameObject> beforeChange = null)
        {
            var plan = BuildPlan(model);
            var sb = new StringBuilder();
            int applied = 0, already = 0;

            foreach (var (t, to, why) in plan)
            {
                if (t.name == to) { already++; continue; }
                sb.AppendLine($"  {t.name,-14} → {to,-32} ({why})");
                if (!dryRun)
                {
                    beforeChange?.Invoke(t.gameObject);
                    t.name = to;
                }
                applied++;
            }

            if (already > 0) sb.AppendLine($"  이미 맞는 이름 {already}개는 건너뛰었다.");
            if (applied == 0 && already == 0) sb.AppendLine("  대상이 없다.");
            return sb.ToString();
        }

        /// <summary>스케일 참조 마커를 끈다. 방 바깥에 떠 있어 화면에 보인다.</summary>
        public static int DisableScaleMarkers(Transform model, bool dryRun, System.Action<GameObject> beforeChange = null)
        {
            int n = 0;
            foreach (var name in ScaleMarkers)
            {
                var t = FindChild(model, name);
                if (t == null || !t.gameObject.activeSelf) continue;
                if (!dryRun)
                {
                    beforeChange?.Invoke(t.gameObject);
                    t.gameObject.SetActive(false);
                }
                n++;
            }
            return n;
        }

        /// <summary>
        /// 같은 이름을 가진 메시가 둘 이상이면 Unity 가 "Identifier uniqueness violation" 을 내고
        /// 재임포트 때 재연결을 보장하지 못한다. 뒤에 나온 것에 접미사를 붙여 유일하게 만든다.
        /// </summary>
        public static int MakeMeshNamesUnique(Transform model, bool dryRun)
        {
            var seen = new Dictionary<string, int>();
            var done = new HashSet<Mesh>();
            int renamed = 0;

            foreach (var mf in model.GetComponentsInChildren<MeshFilter>(true))
            {
                var mesh = mf.sharedMesh;
                if (mesh == null || !done.Add(mesh)) continue;   // 같은 메시를 여러 곳이 쓰는 것은 정상

                if (seen.TryGetValue(mesh.name, out int count))
                {
                    seen[mesh.name] = count + 1;
                    if (!dryRun) mesh.name = $"{mesh.name} #{count + 1}";
                    renamed++;
                }
                else seen[mesh.name] = 1;
            }
            return renamed;
        }

        /// <summary>
        /// 모델 내장 머티리얼에 GPU 인스턴싱을 켠다.
        ///
        /// 왜 이것으로 충분한가 — 사물함이 무거운 이유는 조각이 많아서가 아니라
        /// **같은 메시를 개별 드로우콜로 그리기 때문**이다. 실측하면 잠금장치 아래
        /// `MeshFilter` 1250개가 **서로 다른 메시 16개 · 머티리얼 5개**만 쓴다.
        /// 즉 이미 공유돼 있으므로 모델을 다시 만들 필요 없이 인스턴싱만 켜면
        /// GPU 가 같은 메시+머티리얼 조합을 한 번에 그린다.
        ///
        /// 안 켜져 있으면 아무 효과가 없다 — SketchUp 임포터는 기본값 off 로 만든다.
        /// 인스턴싱이 무의미한 머티리얼(1회만 쓰이는 것)에 켜도 손해는 없다.
        /// </summary>
        public static int EnableGpuInstancing(Transform model, bool dryRun)
        {
            var mats = model.GetComponentsInChildren<MeshRenderer>(true)
                .SelectMany(r => r.sharedMaterials)
                .Where(m => m != null && !m.enableInstancing)
                .Distinct()
                .ToArray();
            if (!dryRun)
                foreach (var m in mats) m.enableInstancing = true;
            return mats.Length;
        }

        // ── 규칙 ──────────────────────────────────────────────────────────────

        /// <summary>
        /// 끝벽 6장. 방 z 양끝에 붙은 평면 그룹을 rear/front 로 나누고
        /// x 내림차순으로 elevatorside / center / windowside 를 붙인다.
        /// 좌우 기준은 모델 자신의 어휘를 따른다 — `wall-elevatorside`(x 최대) · `wall-windowside`(x 최소).
        /// </summary>
        static void PlanEndWalls(Transform model, Bounds room, List<(Transform, string, string)> plan)
        {
            float flatMax = room.size.z * FlatRatio;
            float zTol = room.size.z * EndWallZToleranceRatio;
            float minHeight = room.size.y * WallHeightRatio;
            float xMin = room.min.x - room.size.x * InRoomMargin;
            float xMax = room.max.x + room.size.x * InRoomMargin;

            foreach (var (label, targetZ) in new[] { ("rear", room.min.z), ("front", room.max.z) })
            {
                var walls = model.Cast<Transform>()
                    .Where(c => c.GetComponentsInChildren<MeshFilter>(true).Any(f => f.sharedMesh != null))
                    .Select(c => (t: c, b: LocalBounds(model, c)))
                    .Where(x => x.b.size.z < flatMax                        // 얇다
                             && x.b.size.y > minHeight                      // 벽 높이다 (스케일 마커 배제)
                             && x.b.center.x > xMin && x.b.center.x < xMax  // 방 안이다 (외곽 벽 배제)
                             && Mathf.Abs(x.b.center.z - targetZ) < zTol)   // 방 z 끝단이다
                    .OrderByDescending(x => x.b.center.x)
                    .ToArray();
                if (walls.Length == 0) continue;

                // 3장(엘리베이터측·중앙·창측)이 정상이다. 다른 수가 나오면 규칙이 엉뚱한 것을
                // 잡았다는 뜻이므로 조용히 01·02… 로 넘기지 않고 경고한다 — 처음에 스케일 마커를
                // 끝벽으로 잡아 `wall-rear-04`, `wall-rear-05` 가 생겼던 것을 이 경고가 잡아준다.
                if (walls.Length != 3)
                    Debug.LogWarning($"[WorldModelNaming] {label} 끝벽이 3장이 아니라 {walls.Length}장 잡혔다 — " +
                                     $"판정 기준을 확인해라: {string.Join(", ", walls.Select(w => $"{w.t.name}(x={w.b.center.x:F2},h={w.b.size.y:F2})"))}");

                var side = SideNames(walls.Length);
                for (int i = 0; i < walls.Length; i++)
                {
                    string name = $"wall-{label}-{side[i]}";
                    plan.Add((walls[i].t, name, $"z≈{targetZ:F1} 평면, x={walls[i].b.center.x:F1}"));
                    PlanPanels(model, walls[i].t, name, plan);
                }
            }
        }

        static void PlanPanels(Transform model, Transform wall, string wallName, List<(Transform, string, string)> plan)
        {
            var panels = wall.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Select(c => (t: c, b: LocalBounds(model, c)))
                .OrderByDescending(x => x.b.center.x)
                .ToArray();
            for (int i = 0; i < panels.Length; i++)
                plan.Add((panels[i].t, $"{wallName}-panel-{i + 1:00}", $"x={panels[i].b.center.x:F1}"));
        }

        static void PlanSeats(Transform room, Transform model, List<(Transform, string, string)> plan)
        {
            var seats = room.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group ") && HasMaterial(c, SeatMaterial))
                .Select(c => (t: c, b: LocalBounds(model, c)))
                .OrderBy(x => x.b.center.z)
                .ToArray();
            for (int i = 0; i < seats.Length; i++)
                plan.Add((seats[i].t, $"sofa-{i + 1:00}", $"머티리얼 {SeatMaterial}, z={seats[i].b.center.z:F1}"));
        }

        /// <summary>
        /// 라운지 존. 바닥·배경이 한 메시에 들어 있어 어느 쪽인지 단정하지 않는 이름을 쓴다.
        /// </summary>
        static void PlanLoungeZone(Transform room, Transform model, List<(Transform, string, string)> plan)
        {
            var found = room.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group ") && HasMaterial(c, WoodFloorMaterial))
                .Select(c => (t: c, b: LocalBounds(model, c)))
                .OrderByDescending(x => x.b.size.z)
                .FirstOrDefault();
            if (found.t != null)
                plan.Add((found.t, "lounge-zone", $"머티리얼 {WoodFloorMaterial}, z폭 {found.b.size.z:F1}"));
        }

        /// <summary>
        /// 천장 배경판. 나머지 잎 메시(조명 타일 등)는 부모 이름이 이미 무엇인지 말해 주므로 두었다.
        /// </summary>
        static void PlanCeilingBackdrop(Transform model, List<(Transform, string, string)> plan)
        {
            var ceiling = FindChild(model, CeilingName);
            if (ceiling == null) return;
            var found = ceiling.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Select(c => (t: c, b: LocalBounds(model, c)))
                .OrderByDescending(x => x.b.size.x * x.b.size.z)
                .FirstOrDefault();
            if (found.t != null)
                plan.Add((found.t, "ceiling-backdrop", $"천장 최대 평면 {found.b.size.x:F1}×{found.b.size.z:F1}"));
        }

        // ── 도구 ──────────────────────────────────────────────────────────────

        static string[] SideNames(int count) => count switch
        {
            1 => new[] { "center" },
            2 => new[] { "elevatorside", "windowside" },
            3 => new[] { "elevatorside", "center", "windowside" },
            _ => Enumerable.Range(1, count).Select(i => $"{i:00}").ToArray(),
        };

        static bool HasMaterial(Transform t, string materialName) =>
            t.GetComponentsInChildren<MeshRenderer>(true)
             .SelectMany(r => r.sharedMaterials)
             .Any(m => m != null && m.name == materialName);

        static Transform FindChild(Transform root, string name) =>
            root.GetComponentsInChildren<Transform>(true).FirstOrDefault(t => t.name == name);

        /// <summary>
        /// `root` 로컬 공간의 bounds. 메시의 8 코너를 직접 변환한다 —
        /// `Renderer.bounds` 는 임포트 중에는 신뢰할 수 없다.
        /// </summary>
        static Bounds LocalBounds(Transform root, Transform t)
        {
            bool first = true;
            var result = new Bounds();
            foreach (var mf in t.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.sharedMesh == null) continue;
                var mb = mf.sharedMesh.bounds;
                for (int i = 0; i < 8; i++)
                {
                    var corner = new Vector3(
                        (i & 1) == 0 ? mb.min.x : mb.max.x,
                        (i & 2) == 0 ? mb.min.y : mb.max.y,
                        (i & 4) == 0 ? mb.min.z : mb.max.z);
                    var p = root.InverseTransformPoint(mf.transform.TransformPoint(corner));
                    if (first) { result = new Bounds(p, Vector3.zero); first = false; }
                    else result.Encapsulate(p);
                }
            }
            return result;
        }
    }

    /// <summary>
    /// 11층 월드 모델을 임포트할 때 계층 이름을 자동으로 정리한다.
    ///
    /// 왜 임포트 시점인가 —
    /// 씬에서 이름을 바꾸면 `m_Name` **프리팹 오버라이드**가 되어 모델을 교체하면 사라진다.
    /// 여기서 바꾸면 **애셋 자체**의 이름이 되므로 오버라이드가 남지 않고, 새 `.skp` 를
    /// 넣을 때마다 자동으로 적용된다. 메뉴를 누를 필요도, 모델러에게 부탁할 필요도 없다.
    ///
    /// 대상 판별은 파일명이 아니라 `ssafy-11th-room` 자식 유무로 한다 — 파일명이 매번 바뀐다.
    /// </summary>
    public sealed class WorldModelPostprocessor : AssetPostprocessor
    {
        /// <summary>
        /// 임포터 문제를 진단할 때 후처리기를 끄는 스위치. 끈 상태로 재임포트해서
        /// 경고가 그대로 나오면 원인이 후처리기가 아니라 원본 모델이라는 뜻이다.
        /// </summary>
        const string DisableKey = "Festa.WorldModelPostprocessor.Disabled";
        public static bool Disabled
        {
            get => EditorPrefs.GetBool(DisableKey, false);
            set => EditorPrefs.SetBool(DisableKey, value);
        }

        void OnPostprocessModel(GameObject root)
        {
            if (Disabled) return;
            var t = root.transform;
            if (!WorldModelNaming.IsWorldModel(t)) return;

            // 마커를 먼저 끈다 — 이름으로 찾으므로 재명명 뒤에는 찾을 수 없다.
            int markers = WorldModelNaming.DisableScaleMarkers(t, dryRun: false);
            string report = WorldModelNaming.Apply(t, dryRun: false);
            int instanced = WorldModelNaming.EnableGpuInstancing(t, dryRun: false);

            // 메시 이름 유일화(`MakeMeshNamesUnique`)는 여기서 호출하지 않는다 — T-173 참고.
            // 서브애셋 이름을 바꿔도 `Identifier uniqueness violation` 경고는 그대로 나오고
            // (경고는 후처리기보다 먼저 발생한다), 대신 임포터가 비결정적이라는
            // `generated inconsistent result` 경고를 새로 만든다. 이득 없이 위험만 늘었다.
            // 근본 해결은 SketchUp 에서 두 오브젝트 중 하나를 다른 이름으로 바꾸는 것이다.

            Debug.Log(
                $"[WorldModelPostprocessor] 임포트 시 정리 — {assetPath}\n" +
                report +
                $"  스케일 마커 비활성  : {markers}개 (mock-floor / mock-wall)\n" +
                $"  GPU 인스턴싱 켜기   : 머티리얼 {instanced}개\n" +
                "  애셋 자체를 고쳤다 — 씬에 오버라이드가 남지 않는다.");
        }
    }
}
