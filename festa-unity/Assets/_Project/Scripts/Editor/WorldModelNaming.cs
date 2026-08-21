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
        /// 각 상위 노드의 **자식 서브트리**를 머티리얼별 서브메시 하나로 병합한다.
        ///
        /// 왜 — 사물함 잠금장치 하나가 12개 오브젝트라 모델 전체가 2043개다.
        /// 오브젝트 수는 씬 로드·계층 순회·WebGL 메모리에 그대로 비용이 된다.
        /// GPU 인스턴싱은 드로우콜만 줄이고 오브젝트 수는 못 줄인다.
        ///
        /// 설계 결정 —
        ///   - **노드 자신의 메시는 남긴다.** 병합 결과는 `<이름> (combined)` 자식 하나에 담는다.
        ///     `wall-elevatorside` 는 자기 벽 메시(776 verts)에 MeshCollider 를 붙이는 것이
        ///     콜리전 계획인데, 본체에 78만 verts 병합 메시를 얹으면 그 계획이 깨진다.
        ///   - **노드 단위로만 합친다** (room 자식끼리 교차 병합 금지). 이름·콜리전 대상의
        ///     의미 단위를 지키기 위해서다.
        ///   - **ceiling 은 통째로 건너뛴다.** 조명 도구가 기구 오브젝트 이름과 머티리얼
        ///     슬롯 구조에 의존한다 (WorldCeilingSetup).
        ///   - 음수 스케일(SketchUp 미러 인스턴스)은 삼각형 감기를 뒤집어 보정한다.
        ///     CombineMeshes 는 이것을 처리하지 않으므로 직접 굽는다.
        ///
        /// 임포트 중에만 쓸 수 있다 — 씬의 프리팹 인스턴스에서는 자식 삭제가 불가능하다.
        /// </summary>
        public static (int nodes, int removedRenderers, string report) CombineSubtrees(
            Transform model, System.Action<string, Mesh> registerMesh)
        {
            var targets = new List<Transform>();
            foreach (Transform c in model)
            {
                if (c.name == CeilingName) continue;
                if (c.name == RoomName) { foreach (Transform rc in c) targets.Add(rc); }
                else targets.Add(c);
            }
            return CombineNodes(targets, registerMesh);
        }

        /// <summary>
        /// 엘리베이터용 병합. 루트 직속 노드마다 자식 서브트리를 하나로 굽는다.
        ///
        /// **문은 제외한다** — 열려야 하므로 독립 오브젝트로 남아야 한다. 지금은 자식이
        /// 없어서 어차피 건너뛰지만, 나중 export 가 문 아래에 손잡이라도 넣으면 삼켜진다.
        /// 의도를 코드로 못박아 둔다.
        ///
        /// 무게는 버튼 패널에 있다 — 정점 419k 중 404k(96%)가 패널 2개다. 11층 사물함과
        /// 같은 형태(같은 메시를 개별 드로우콜로 그린다)라 같은 처방이 듣는다.
        /// </summary>
        public static (int nodes, int removedRenderers, string report) CombineElevatorSubtrees(
            Transform model, System.Action<string, Mesh> registerMesh)
        {
            var targets = model.Cast<Transform>()
                .Where(c => !c.name.StartsWith(ElevatorDoorPrefix))
                .ToList();
            return CombineNodes(targets, registerMesh);
        }

        static (int nodes, int removedRenderers, string report) CombineNodes(
            List<Transform> targets, System.Action<string, Mesh> registerMesh)
        {
            var sb = new StringBuilder();
            int nodes = 0, removed = 0;
            foreach (var node in targets)
            {
                int childRenderers = node.GetComponentsInChildren<MeshFilter>(true)
                    .Count(f => f.transform != node && f.sharedMesh != null);
                if (childRenderers < 2) continue;   // 합칠 것이 없다

                var (mesh, mats) = BakeChildren(node);
                if (mesh == null) continue;

                registerMesh($"combined:{node.name}", mesh);

                // 자식을 전부 지우고 병합 결과 하나로 대체한다.
                for (int i = node.childCount - 1; i >= 0; i--)
                    Object.DestroyImmediate(node.GetChild(i).gameObject);

                var go = new GameObject($"{node.name} (combined)");
                go.transform.SetParent(node, false);
                go.AddComponent<MeshFilter>().sharedMesh = mesh;
                go.AddComponent<MeshRenderer>().sharedMaterials = mats;

                sb.AppendLine($"    {node.name,-24} 렌더러 {childRenderers,4} → 1  (정점 {mesh.vertexCount:N0}, 서브메시 {mats.Length})");
                nodes++;
                removed += childRenderers - 1;
            }
            return (nodes, removed, sb.ToString());
        }

        /// <summary>
        /// 노드의 자식 메시를 머티리얼별로 묶어 서브메시 하나짜리 메시로 굽는다.
        /// 노드 자신의 MeshFilter 는 제외한다.
        /// </summary>
        static (Mesh mesh, Material[] mats) BakeChildren(Transform node)
        {
            var verts = new List<Vector3>();
            var normals = new List<Vector3>();
            var uvs = new List<Vector2>();
            var matOrder = new List<Material>();                 // 등장 순서 고정 — 결정적 임포트
            var trisByMat = new Dictionary<Material, List<int>>();

            // 같은 메시 16개를 1250곳에서 쓰므로 정점 데이터는 메시당 한 번만 읽는다.
            var cache = new Dictionary<Mesh, (Vector3[] v, Vector3[] n, Vector2[] u)>();

            foreach (var mf in node.GetComponentsInChildren<MeshFilter>(true))
            {
                if (mf.transform == node || mf.sharedMesh == null) continue;
                var r = mf.GetComponent<MeshRenderer>();
                if (r == null) continue;

                var m = mf.sharedMesh;
                if (!cache.TryGetValue(m, out var data))
                {
                    var u = m.uv;
                    if (u.Length != m.vertexCount) u = new Vector2[m.vertexCount];
                    data = (m.vertices, m.normals, u);
                    if (data.n.Length != m.vertexCount) data.n = new Vector3[m.vertexCount];
                    cache[m] = data;
                }

                var toNode = node.worldToLocalMatrix * mf.transform.localToWorldMatrix;
                var normalMat = toNode.inverse.transpose;
                bool flip = toNode.determinant < 0f;             // 미러 인스턴스 → 감기 뒤집기

                int baseIndex = verts.Count;
                for (int i = 0; i < data.v.Length; i++)
                {
                    verts.Add(toNode.MultiplyPoint3x4(data.v[i]));
                    normals.Add(((Vector3)(normalMat * data.n[i])).normalized);
                    uvs.Add(data.u[i]);
                }

                var rMats = r.sharedMaterials;
                for (int si = 0; si < m.subMeshCount; si++)
                {
                    var mat = si < rMats.Length ? rMats[si] : null;
                    if (mat == null) continue;
                    if (!trisByMat.TryGetValue(mat, out var list))
                    {
                        list = new List<int>();
                        trisByMat[mat] = list;
                        matOrder.Add(mat);
                    }
                    var tris = m.GetTriangles(si);
                    if (flip)
                        for (int i = 0; i < tris.Length; i += 3)
                        { list.Add(tris[i] + baseIndex); list.Add(tris[i + 2] + baseIndex); list.Add(tris[i + 1] + baseIndex); }
                    else
                        for (int i = 0; i < tris.Length; i++) list.Add(tris[i] + baseIndex);
                }
            }

            if (verts.Count == 0 || matOrder.Count == 0) return (null, null);

            var mesh = new Mesh
            {
                name = $"{node.name} (combined)",
                indexFormat = verts.Count > 65535
                    ? UnityEngine.Rendering.IndexFormat.UInt32
                    : UnityEngine.Rendering.IndexFormat.UInt16,
            };
            mesh.SetVertices(verts);
            mesh.SetNormals(normals);
            mesh.SetUVs(0, uvs);
            mesh.subMeshCount = matOrder.Count;
            for (int i = 0; i < matOrder.Count; i++)
                mesh.SetTriangles(trisByMat[matOrder[i]], i);
            mesh.RecalculateBounds();
            return (mesh, matOrder.ToArray());
        }

        // ── 콜리전 ────────────────────────────────────────────────────────────
        // 씬이 아니라 **임포트된 애셋에** 붙인다. 씬에 붙이면 프리팹 오버라이드라
        // 모델 교체 때 사라진다 (T-156 이 그렇게 났다). 여기 붙이면 재임포트마다 자동이다.
        //
        // 대상 이름은 우리 후처리기가 직접 붙인 것이라 열거해도 안전하다 —
        // export 마다 바뀌는 `Group N` 과 달리 이 이름들은 지오메트리에서 유도된다.
        //
        // 형태별 원칙 (T-167 의 "다 네모로 하지 말 것" 유지):
        //   메시  — 걷는 면·벽·곡면 가구. 정점이 적어(78~2만) non-convex 정적 BVH 로 싸다.
        //   캡슐  — 기둥·사람. 박스로 하면 모서리에 걸려 이동이 끊긴다.
        //   끝벽  — 자기 메시가 없다. (combined) 자식 메시가 **노드 로컬 공간에 구워져**
        //           있으므로 그 메시를 노드의 MeshCollider 에 그대로 문다 (116~174 verts).
        //
        // 일부러 안 붙이는 것: wall-elevatorside 의 (combined) 78만 verts 장식,
        // y≈34 의 사이니지(EDUCATION 등 — 플레이어 키 13.5 위), ceiling, 방 밖 wall-flat.

        static readonly string[] MeshColliderTargets =
        {
            "floor",                // 걷는 면
            "wall-elevatorside",    // 본체 벽 메시 776v — (combined) 도어락은 제외
            "wall-windowside",
            "counter-plain",
            "counter-samsung",
            "SSAFY-center",
            "content-introduction", // 개구부 있는 전시벽 — 박스로 막으면 못 지나간다
            "content-education",    // 하단 y=10 — 플레이어 머리(13.5)와 겹친다
            "lounge-zone",          // 데크 0.32 단차 — stepOffset 3.0 으로 올라간다
        };
        const string SofaPrefix = "sofa-";           // 곡면 소파 — 메시가 정확하다
        static readonly string[] CapsuleColliderTargets = { "cylinder", "cylinder 1", "Niraj" };
        static readonly string[] EndWallPrefixes = { "wall-rear-", "wall-front-" };

        /// <summary>임포트된 모델에 월드 콜리전을 붙인다. 병합(CombineSubtrees) 뒤에 불러야 한다.</summary>
        public static (int mesh, int capsule, string report) AddColliders(Transform model)
        {
            var room = FindChild(model, RoomName);
            if (room == null) return (0, 0, "  방 없음 — 콜리전 생략\n");

            int nMesh = 0, nCapsule = 0;
            var sb = new StringBuilder();

            void AddMesh(Transform t, Mesh m, string why)
            {
                if (t == null || m == null) { sb.AppendLine($"    ⚠ 콜리전 대상 없음 ({why})"); return; }
                var mc = GetOrAdd<MeshCollider>(t);
                mc.sharedMesh = m;
                mc.convex = false;   // 정적 지오메트리 — non-convex 가 정확하고 BVH 질의로 싸다
                nMesh++;
            }

            foreach (var name in MeshColliderTargets)
            {
                var t = room.Find(name);
                AddMesh(t, t != null ? t.GetComponent<MeshFilter>()?.sharedMesh : null, name);
            }

            foreach (Transform c in room)
                if (c.name.StartsWith(SofaPrefix))
                    AddMesh(c, c.GetComponent<MeshFilter>()?.sharedMesh, c.name);

            // 방 노드 자체 메시 (있으면 — 내부 구조물)
            var roomMf = room.GetComponent<MeshFilter>();
            if (roomMf != null && roomMf.sharedMesh != null)
                AddMesh(room, roomMf.sharedMesh, RoomName);

            // 끝벽 — (combined) 자식 메시는 노드 로컬 공간이라 노드 콜라이더에 그대로 맞는다
            foreach (Transform c in model)
            {
                if (!EndWallPrefixes.Any(p => c.name.StartsWith(p))) continue;
                var comb = c.GetComponentsInChildren<MeshFilter>(true)
                            .FirstOrDefault(f => f.sharedMesh != null);
                AddMesh(c, comb != null ? comb.sharedMesh : null, c.name);
            }

            foreach (var name in CapsuleColliderTargets)
            {
                var t = room.Find(name);
                if (t == null) { sb.AppendLine($"    ⚠ 캡슐 대상 없음 ({name})"); continue; }
                var b = LocalBounds(t, t);
                if (b.size == Vector3.zero) continue;
                var cc = GetOrAdd<CapsuleCollider>(t);
                // 가장 긴 로컬 축을 캡슐 축으로 잡는다 — SketchUp Z-up 변환이 노드 로컬
                // 축을 돌려놓아 세로가 Y 라는 보장이 없다 (구 TryFitCapsule 의 교훈).
                var s = b.size;
                int axis = s.x >= s.y && s.x >= s.z ? 0 : (s.y >= s.z ? 1 : 2);
                int a2 = (axis + 1) % 3, b2 = (axis + 2) % 3;
                cc.center = b.center;
                cc.direction = axis;
                cc.height = s[axis];
                cc.radius = Mathf.Max(s[a2], s[b2]) * 0.5f;
                nCapsule++;
            }

            sb.AppendLine($"    Mesh {nMesh} / Capsule {nCapsule}");
            return (nMesh, nCapsule, sb.ToString());
        }

        static T GetOrAdd<T>(Transform t) where T : Component
        {
            var c = t.GetComponent<T>();
            return c == null ? t.gameObject.AddComponent<T>() : c;   // ?? 금지 — Unity 가짜 null (T-168)
        }

        // ── 엘리베이터 ────────────────────────────────────────────────────────
        // 별도 지역으로 관리할 예정이라(2026-08-21 방향) 11층 모델과 다른 규칙을 쓴다.
        // 원본이 이미 의미 이름을 갖고 있어 정리할 것이 적다 — 오타 하나와 export 접미사뿐이다.

        public const string ElevatorShellName = "elevator";
        public const string ElevatorDoorPrefix = "elevator-door";
        const string ElevatorSwitchName = "elevator-switch";

        /// <summary>양문이 둘 다 있으면 엘리베이터 모델이다. 파일명이 아니라 구조로 판별한다.</summary>
        public static bool IsElevatorModel(Transform root) =>
            root.Find("elevator-door-left") != null && root.Find("elevator-door-right") != null;

        /// <summary>
        /// 루트 직속 이름 정리. 병합이 자식을 지우므로 **루트 직속 이름만 살아남는다** —
        /// 안쪽 `Group N` 490개는 병합으로 사라지니 따로 손대지 않는다.
        /// </summary>
        public static string TidyElevatorNames(Transform model)
        {
            var renames = new (string from, string to)[]
            {
                // 원본 오타(disablity)를 고치고, 무엇을 위한 패널인지 이름에 담는다.
                ("elevator-button-disablity",     "elevator-panel-accessible"),
                // "-resized" 는 모델링 과정의 흔적이라 의미가 없다.
                ("elevator-button-group-resized", "elevator-panel-main"),
            };
            var sb = new StringBuilder();
            foreach (var (from, to) in renames)
            {
                var t = model.Find(from);
                if (t == null) continue;
                t.name = to;
                sb.AppendLine($"    {from,-30} → {to}");
            }
            return sb.ToString();
        }

        /// <summary>
        /// 엘리베이터 콜리전. 형태별로 최소한만 붙인다.
        ///   셸  — 자기 메시(246 verts)로 MeshCollider. 칸 안을 걸어다닐 수 있어야 한다.
        ///         병합 결과(손잡이)에는 붙이지 않는다 — 손잡이에 걸릴 이유가 없다.
        ///   문  — 각각 MeshCollider(80 verts). **여닫아야 하므로 독립 콜라이더여야 한다.**
        ///   스위치 — BoxCollider. 상호작용 레이캐스트 대상이다. 병합 메시가 12k verts 라
        ///         MeshCollider 는 낭비다.
        ///   패널 — 없음. 장식이고 스위치가 상호작용을 담당한다.
        /// </summary>
        public static (int mesh, int box, string report) AddElevatorColliders(Transform model)
        {
            int nMesh = 0, nBox = 0;
            var sb = new StringBuilder();

            var shell = model.Find(ElevatorShellName);
            var shellMesh = shell != null ? shell.GetComponent<MeshFilter>()?.sharedMesh : null;
            if (shellMesh != null)
            {
                var mc = GetOrAdd<MeshCollider>(shell);
                mc.sharedMesh = shellMesh;
                mc.convex = false;
                nMesh++;
                sb.AppendLine($"    {ElevatorShellName} MeshCollider ({shellMesh.vertexCount} verts)");
            }
            else sb.AppendLine($"    ⚠ {ElevatorShellName} 의 자기 메시가 없다 — 칸 콜리전 없음");

            foreach (Transform c in model)
            {
                if (!c.name.StartsWith(ElevatorDoorPrefix)) continue;
                var m = c.GetComponent<MeshFilter>()?.sharedMesh;
                if (m == null) { sb.AppendLine($"    ⚠ {c.name} 메시 없음"); continue; }
                var mc = GetOrAdd<MeshCollider>(c);
                mc.sharedMesh = m;
                mc.convex = false;
                nMesh++;
                sb.AppendLine($"    {c.name} MeshCollider ({m.vertexCount} verts, 여닫이용 독립)");
            }

            var sw = model.Find(ElevatorSwitchName);
            if (sw != null)
            {
                var b = LocalBounds(sw, sw);
                if (b.size != Vector3.zero)
                {
                    var bc = GetOrAdd<BoxCollider>(sw);
                    bc.center = b.center;
                    bc.size = b.size;
                    nBox++;
                    sb.AppendLine($"    {ElevatorSwitchName} BoxCollider (상호작용 대상)");
                }
            }
            return (nMesh, nBox, sb.ToString());
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

        const string ModelsFolder = "Assets/_Project/Models/";

        /// <summary>
        /// 건축 모델인가 — `Models/` **직속** 파일만 해당한다.
        ///
        /// 하위 폴더를 제외하는 이유가 있다. `Models/Laptop/laptop.FBX` 는 노멀맵을 쓰는데
        /// (`laptop.mat` 에 `_NORMALMAP` 키워드와 `_BumpMap` 슬롯), 아래 `importTangents = None`
        /// 이 그 파일까지 덮으면 재임포트되는 순간 음영이 깨진다. 탄젠트 제거의 근거는
        /// "월드 모델 머티리얼 42개 중 노멀맵 0개" 라는 **실측**이고 그 근거는 다른 모델로
        /// 전이되지 않는다. 처음에 폴더 전체로 잡아 이 잠복 버그를 만들었다 (T-178).
        ///
        /// 건축 모델(11층 월드·엘리베이터)은 `Models/` 직속에 둔다. 노멀맵을 쓰는 소품은
        /// 하위 폴더에 둔다 — 그러면 이 설정이 닿지 않는다.
        /// </summary>
        static bool IsArchitecturalModel(string path) =>
            path.StartsWith(ModelsFolder) &&
            path.IndexOf('/', ModelsFolder.Length) < 0;   // 직속 = 이후 '/' 없음

        /// <summary>
        /// 임포터 설정. `SketchUpImporter` 는 `ModelImporter` 를 상속하므로 FBX 와 같은
        /// 설정이 `.skp` 에도 그대로 적용된다 — 별도 포맷 전환이 필요 없다.
        ///
        /// 이 단계에서는 모델이 아직 임포트되지 않아 계층으로 대상을 판별할 수 없으므로
        /// 경로로 거른다 (<see cref="IsArchitecturalModel"/>).
        /// </summary>
        void OnPreprocessModel()
        {
            if (Disabled) return;
            if (!IsArchitecturalModel(assetPath)) return;
            var mi = assetImporter as ModelImporter;
            if (mi == null) return;

            // 탄젠트 제거 — 월드 모델 머티리얼 42개 중 노멀맵 사용이 0개라 실측으로 확인했다.
            // 정점당 Float32x4 = 16 byte 를 그냥 버리고 있었다.
            // 노멀맵을 쓰는 모델이 이 범위에 들어오면 OnPostprocessModel 이 경고한다.
            mi.importTangents = ModelImporterTangents.None;
            mi.importBlendShapes = false;   // 건축 모델에 블렌드셰이프 없음
            mi.importLights = false;        // 조명은 WorldCeilingSetup 이 만든다
            mi.importCameras = false;
            // 직렬화 압축 — 빌드(WebGL 다운로드) 크기용. 런타임 메모리는 그대로다.
            // 방 폭 217 unit 기준 16-bit 양자화 오차 ≈ 0.003 unit — 시각적으로 무의미.
            // 단 임포트 후 검증(바닥 y·천장 오차)이 틀어지면 Off 로 되돌린다.
            mi.meshCompression = ModelImporterMeshCompression.Medium;
        }

        /// <summary>
        /// 탄젠트를 지운 모델이 실제로 노멀맵을 쓰면 경고한다. 탄젠트 없이 노멀맵을 쓰면
        /// 음영이 조용히 틀어지므로 — 화면을 보고 알아채기 전에 로그로 잡는다.
        /// </summary>
        static void WarnIfNormalMapWithoutTangents(GameObject root, string path)
        {
            var offenders = root.GetComponentsInChildren<MeshRenderer>(true)
                .SelectMany(r => r.sharedMaterials)
                .Where(m => m != null && m.HasProperty("_BumpMap") && m.GetTexture("_BumpMap") != null)
                .Select(m => m.name).Distinct().ToArray();
            if (offenders.Length == 0) return;

            Debug.LogWarning(
                $"[WorldModelPostprocessor] {path} 의 머티리얼 {offenders.Length}개가 노멀맵을 쓴다: " +
                $"{string.Join(", ", offenders.Take(5))}\n" +
                "  이 경로는 탄젠트를 제거하는 범위(Models/ 직속)다 — 탄젠트 없이 노멀맵을 쓰면 음영이 틀어진다.\n" +
                "  이 모델을 하위 폴더로 옮기거나(권장), IsArchitecturalModel 판정을 좁혀라.");
        }

        void OnPostprocessModel(GameObject root)
        {
            if (Disabled) return;
            var t = root.transform;
            // 탄젠트 제거 범위에 들어온 모델이 노멀맵을 쓰면 경고한다 (월드/엘리베이터 공통).
            if (IsArchitecturalModel(assetPath)) WarnIfNormalMapWithoutTangents(root, assetPath);

            if (WorldModelNaming.IsElevatorModel(t)) { ProcessElevator(t); return; }
            if (!WorldModelNaming.IsWorldModel(t)) return;

            // 마커를 먼저 끈다 — 이름으로 찾으므로 재명명 뒤에는 찾을 수 없다.
            int markers = WorldModelNaming.DisableScaleMarkers(t, dryRun: false);
            string report = WorldModelNaming.Apply(t, dryRun: false);
            // 병합은 이름 정리 뒤 — 결과 오브젝트가 정리된 이름을 물려받는다.
            // 병합 메시는 서브애셋으로 등록해야 임포트 결과에 저장된다.
            var (nodes, removedRenderers, combineReport) =
                WorldModelNaming.CombineSubtrees(t, (id, mesh) => context.AddObjectToAsset(id, mesh));

            var (nColMesh, nColCapsule, colReport) = WorldModelNaming.AddColliders(t);
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
                $"  서브트리 병합       : {nodes}개 노드, 렌더러 {removedRenderers}개 감소\n" +
                combineReport +
                $"  콜라이더            : Mesh {nColMesh} / Capsule {nColCapsule} (애셋에 내장 — 재적용 불필요)\n" +
                colReport +
                $"  GPU 인스턴싱 켜기   : 머티리얼 {instanced}개\n" +
                "  애셋 자체를 고쳤다 — 씬에 오버라이드가 남지 않는다. 벽 개구부 봉쇄만 씬 메뉴로 돌린다.");
        }

        /// <summary>
        /// 엘리베이터 모델 처리. 11층과 규칙이 달라 분리했다 — 별도 지역으로 관리할 예정이고,
        /// 원본이 이미 의미 이름을 갖고 있어 정리할 것이 적다.
        /// </summary>
        void ProcessElevator(Transform t)
        {
            string renames = WorldModelNaming.TidyElevatorNames(t);
            var (nodes, removed, combineReport) =
                WorldModelNaming.CombineElevatorSubtrees(t, (id, mesh) => context.AddObjectToAsset(id, mesh));
            var (nMesh, nBox, colReport) = WorldModelNaming.AddElevatorColliders(t);
            int instanced = WorldModelNaming.EnableGpuInstancing(t, dryRun: false);

            Debug.Log(
                $"[WorldModelPostprocessor] 엘리베이터 정리 — {assetPath}\n" +
                (renames.Length > 0 ? "  이름 정리\n" + renames : "  이름 정리: 없음\n") +
                $"  서브트리 병합 : {nodes}개 노드, 렌더러 {removed}개 감소 (문은 제외 — 여닫이용)\n" +
                combineReport +
                $"  콜라이더      : Mesh {nMesh} / Box {nBox}\n" +
                colReport +
                $"  GPU 인스턴싱  : 머티리얼 {instanced}개\n" +
                "  애셋 자체를 고쳤다 — 씬에 오버라이드가 남지 않는다.");
        }

        /// <summary>
        /// 월드 텍스처 임포터 설정 — WebGL 다운로드 크기용 crunch 압축.
        /// 25장 전부 crunch 미적용 상태였다. 품질 75 는 월드 배경 텍스처에서 식별 불가.
        /// </summary>
        void OnPreprocessTexture()
        {
            if (Disabled) return;
            if (!assetPath.StartsWith("Assets/_Project/Models/MapTexture/")) return;
            var ti = (TextureImporter)assetImporter;
            ti.textureCompression = TextureImporterCompression.Compressed;
            ti.crunchedCompression = true;
            ti.compressionQuality = 75;
        }
    }
}
