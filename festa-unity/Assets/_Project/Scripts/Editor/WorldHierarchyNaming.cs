using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 월드 모델의 `Group N` 이름을 읽을 수 있는 이름으로 바꾼다.
    ///
    /// 왜 도구로 만드는가 —
    /// 모델 인스턴스의 이름 변경은 **프리팹 오버라이드**다. 머티리얼·콜라이더처럼
    /// 모델을 교체하면 사라진다. 손으로 바꾸면 다음 export 때 전부 다시 해야 한다.
    ///
    /// 왜 이름이 아니라 지오메트리로 찾는가 —
    /// `Group N` 의 N 은 SketchUp export 마다 바뀐다. 실제로 0820 → 0821 교체에서
    /// 끝벽이 `Group 4·9·16 / 23·28·35` → `Group 3·8·15 / 22·27·34` 로 전부 밀렸다.
    /// 그래서 이름을 열거하면 다음 모델에서 또 깨진다. 위치·크기·머티리얼로 찾으면
    /// 번호가 어떻게 바뀌어도 같은 대상을 집는다.
    ///
    /// 건드리지 않는 것 —
    ///   - 이미 의미 있는 이름 (`floor`, `wall-elevatorside`, `counter-samsung` …)
    ///   - 사물함 잠금장치 아래의 `Group` 1200개. `lock-doorlock` 50개 × 12개 × 2단이고
    ///     아무도 그 깊이를 뒤지지 않는다. 이름을 바꾸면 씬 파일에 오버라이드만 1200개 늘어난다.
    /// </summary>
    public static class WorldHierarchyNaming
    {
        const string WorldRootName = "@World_11F";
        const string RoomName = "ssafy-11th-room";

        // 방 z 양끝에서 이 거리 안에 있는 평면 오브젝트를 끝벽으로 본다 (world unit).
        const float EndWallZTolerance = 8f;
        // size.z 가 이보다 작으면 "평면" 으로 본다.
        const float FlatThreshold = 2f;
        // 좌석으로 판정하는 머티리얼 이름.
        const string SeatMaterial = "ssafy-sofa";
        // 라운지 존 판정에 쓰는 머티리얼.
        const string WoodFloorMaterial = "[Wood Floor]";

        [MenuItem("Festa/World/계층 이름 정리 (Group → 의미 이름)", false, 110)]
        static void Apply() => Run(false);

        [MenuItem("Festa/World/계층 이름 정리 — 미리보기만", false, 111)]
        static void Preview() => Run(true);

        static void Run(bool dryRun)
        {
            var world = GameObject.Find(WorldRootName);
            if (world == null) { Debug.LogError($"[WorldHierarchyNaming] '{WorldRootName}' 이 씬에 없다."); return; }

            var model = FindModelRoot(world.transform);
            if (model == null)
            {
                Debug.LogError($"[WorldHierarchyNaming] '{RoomName}' 을 가진 모델 인스턴스를 찾지 못했다.");
                return;
            }

            var room = model.Find(RoomName);
            var roomBounds = WorldBounds(room);
            var plan = new List<(Transform t, string to, string why)>();

            PlanEndWalls(model, roomBounds, plan);
            PlanSeats(room, plan);
            PlanLoungeZone(room, plan);
            PlanCeilingBackdrop(model, plan);

            var sb = new StringBuilder();
            sb.AppendLine($"[WorldHierarchyNaming] {(dryRun ? "미리보기" : "적용")} — 대상 {plan.Count}개");
            sb.AppendLine($"  모델: {model.name}");

            int applied = 0, already = 0;
            foreach (var (t, to, why) in plan)
            {
                if (t.name == to) { already++; continue; }
                sb.AppendLine($"  {t.name,-14} → {to,-28} ({why})");
                if (!dryRun)
                {
                    Undo.RecordObject(t.gameObject, "rename world part");
                    t.name = to;
                }
                applied++;
            }

            if (already > 0) sb.AppendLine($"  이미 맞는 이름 {already}개는 건너뛰었다.");
            if (applied == 0) sb.AppendLine("  바꿀 것이 없다.");

            if (!dryRun && applied > 0)
            {
                EditorSceneManager.MarkSceneDirty(world.scene);
                sb.AppendLine("  씬은 저장하지 않았다 — 확인 후 직접 저장한다.");
            }
            sb.AppendLine("  이름 변경은 프리팹 오버라이드다. 모델을 교체하면 사라지므로 교체 후 다시 실행한다.");
            Debug.Log(sb.ToString());
        }

        /// <summary>
        /// 끝벽 6장. 방 z 양끝에 붙은 평면 그룹을 찾아 elevatorside / center / windowside 로 나눈다.
        /// x 가 큰 쪽이 엘리베이터측이다 — 모델 자신의 `wall-elevatorside`(x 최대) ·
        /// `wall-windowside`(x 최소) 명명과 같은 기준을 쓴다.
        /// </summary>
        static void PlanEndWalls(Transform model, Bounds room, List<(Transform, string, string)> plan)
        {
            foreach (var (label, targetZ) in new[] { ("rear", room.min.z), ("front", room.max.z) })
            {
                var walls = model.Cast<Transform>()
                    .Where(c => c.GetComponentsInChildren<MeshRenderer>(true).Length > 0)
                    .Select(c => (t: c, b: WorldBounds(c)))
                    .Where(x => x.b.size.z < FlatThreshold && Mathf.Abs(x.b.center.z - targetZ) < EndWallZTolerance)
                    .OrderByDescending(x => x.b.center.x)   // 엘리베이터측(x 큰 쪽) 먼저
                    .ToArray();

                if (walls.Length == 0) continue;

                var side = SideNames(walls.Length);
                for (int i = 0; i < walls.Length; i++)
                {
                    string name = $"wall-{label}-{side[i]}";
                    plan.Add((walls[i].t, name, $"z≈{targetZ:F0} 평면, x={walls[i].b.center.x:F0}"));
                    PlanPanels(walls[i].t, name, plan);
                }
            }
        }

        /// <summary>끝벽 안의 판 하나하나. 부모 이름을 물려받아 번호를 붙인다.</summary>
        static void PlanPanels(Transform wall, string wallName, List<(Transform, string, string)> plan)
        {
            var panels = wall.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Select(c => (t: c, b: WorldBounds(c)))
                .OrderByDescending(x => x.b.center.x)
                .ToArray();
            for (int i = 0; i < panels.Length; i++)
                plan.Add((panels[i].t, $"{wallName}-panel-{i + 1:00}", $"x={panels[i].b.center.x:F0}"));
        }

        /// <summary>좌석. `ssafy-sofa` 머티리얼로 찾고 z 순서로 번호를 붙인다.</summary>
        static void PlanSeats(Transform room, List<(Transform, string, string)> plan)
        {
            var seats = room.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Where(c => HasMaterial(c, SeatMaterial))
                .Select(c => (t: c, b: WorldBounds(c)))
                .OrderBy(x => x.b.center.z)
                .ToArray();
            for (int i = 0; i < seats.Length; i++)
                plan.Add((seats[i].t, $"sofa-{i + 1:00}", $"머티리얼 {SeatMaterial}, z={seats[i].b.center.z:F0}"));
        }

        /// <summary>
        /// 라운지 존. 좌석이 놓인 구역의 나무 바닥 + 배경을 한 덩어리로 가진 오브젝트다.
        /// 이름을 `lounge-zone` 으로 둔다 — 바닥인지 벽인지 단정하지 않는다 (실제로 둘 다 들어 있다).
        /// </summary>
        static void PlanLoungeZone(Transform room, List<(Transform, string, string)> plan)
        {
            var found = room.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Where(c => HasMaterial(c, WoodFloorMaterial))
                .Select(c => (t: c, b: WorldBounds(c)))
                .OrderByDescending(x => x.b.size.z)
                .FirstOrDefault();
            if (found.t != null)
                plan.Add((found.t, "lounge-zone", $"머티리얼 {WoodFloorMaterial}, z폭 {found.b.size.z:F0}"));
        }

        /// <summary>
        /// 천장 배경판. 천장 전체를 덮는 검은 평면 한 장이라 방 크기에 준하는 폭으로 찾는다.
        ///
        /// 나머지 잎 메시(조명 타일 9개, `SSAFY-center` 안의 메시)는 일부러 두었다. 부모 이름이
        /// 이미 무엇인지 말해 주고, 바꿔도 계층을 읽는 데 도움이 안 되면서 오버라이드만 늘어난다.
        /// </summary>
        static void PlanCeilingBackdrop(Transform model, List<(Transform, string, string)> plan)
        {
            var ceiling = model.Find("ceiling");
            if (ceiling == null) return;
            var found = ceiling.Cast<Transform>()
                .Where(c => c.name.StartsWith("Group "))
                .Select(c => (t: c, b: WorldBounds(c)))
                .OrderByDescending(x => x.b.size.x * x.b.size.z)
                .FirstOrDefault();
            if (found.t != null)
                plan.Add((found.t, "ceiling-backdrop", $"천장 최대 평면 {found.b.size.x:F0}×{found.b.size.z:F0}"));
        }

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

        static Transform FindModelRoot(Transform world)
        {
            foreach (Transform c in world)
                if (c.Find(RoomName) != null) return c;
            return null;
        }

        static Bounds WorldBounds(Transform t)
        {
            var rs = t.GetComponentsInChildren<MeshRenderer>(true);
            if (rs.Length == 0) return new Bounds(t.position, Vector3.zero);
            var b = rs[0].bounds;
            for (int i = 1; i < rs.Length; i++) b.Encapsulate(rs[i].bounds);
            return b;
        }
    }
}
