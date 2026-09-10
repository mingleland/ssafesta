// 축제장의 소형 정적 소품(풍선·벽등·상자·표지판·가로등)을 재질·구역 단위로 한 메시로 굽는다.
// 왜 있는가: WebGL 은 정적 배칭이 꺼져 있어(QA #2) 같은 재질이라도 렌더러 하나가 드로우콜 하나다.
// 축제장 동향 시야에 이런 것이 200개 넘게 들어와 프레임을 먹는다(QA #85, 2026-09-09 사용자 지시
// "관람차나 전구줄 같은 것들도 묶어서 어색하지 않은 애들끼리 다 묶는 게 좋겠다").
// 전구줄(Festoon_Bulbs_Combined)은 이미 같은 방식으로 굽혀 있다 — 그 방식을 나머지에 적용한다.
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    public static class FestivalStaticCombiner
    {
        const string OutDir = "Assets/_Project/Art/Generated/Combined/Festival";
        const string CombinedSuffix = "_Combined";
        const float CellSize = 250f;   // "어색하지 않은 단위" — 이보다 멀리 떨어진 것은 한 메시로 묶지 않는다(컬링 단위 유지)

        /// <summary>
        /// 병합 대상. 렌더러만 묶고 콜라이더·Light·스크립트는 원본에 그대로 둔다.
        /// 애니메이션·회전이 있는 것(관람차 `FerrisWheelSpin`)과 LODGroup(나무)·상호작용(아케이드)은 넣지 않는다.
        /// </summary>
        static readonly string[] Groups =
        {
            "@Festival/Festival_Ambience",
            "@Festival/Festival_WallLights",
            "@Festival/Festival_Clutter",
            "@Festival/Festival_BoothSigns",
            "@Festival/Festival_Lamps",
            "@Festival/Festival_Props",
        };

        [MenuItem("Festa/최적화/축제 정적 소품 병합 (재질·구역별) — QA #85")]
        public static void CombineAll()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[FestivalStaticCombiner] 플레이 모드에서는 돌리지 않는다."); return; }
            EnsureAssetFolder(OutDir);
            var report = new StringBuilder();
            int totalBefore = 0, totalAfter = 0;
            foreach (var path in Groups)
            {
                var group = FindGroup(path);
                if (group == null) { report.AppendLine($"{path}: 없음"); continue; }
                Restore(group.transform, quiet: true);   // 두 번 돌려도 같은 결과
                var (before, after) = CombineGroup(group.transform, report);
                totalBefore += before; totalAfter += after;
            }
            AssetDatabase.SaveAssets();
            UnityEngine.SceneManagement.SceneManager.GetActiveScene();
            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(UnityEngine.SceneManagement.SceneManager.GetActiveScene());
            report.Insert(0, $"[FestivalStaticCombiner] 렌더러 {totalBefore} → {totalAfter} (병합 메시). 원본 렌더러는 꺼 두었고 콜라이더·Light·스크립트는 그대로다.\n");
            Debug.Log(report.ToString());
        }

        [MenuItem("Festa/최적화/축제 정적 소품 병합 복원 (원본 렌더러 켜기)")]
        public static void RestoreAll()
        {
            foreach (var path in Groups)
            {
                var group = FindGroup(path);
                if (group != null) Restore(group.transform, quiet: false);
            }
            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(UnityEngine.SceneManagement.SceneManager.GetActiveScene());
        }

        static (int before, int after) CombineGroup(Transform group, StringBuilder report)
        {
            // 병합 대상 렌더러: 켜져 있고, 재질 1개, 메시 있음, 파티클·Unlit 빔 같은 것(Quad)은 제외
            var sources = group.GetComponentsInChildren<MeshRenderer>(false)
                .Where(r => r.enabled && r.gameObject.activeInHierarchy)
                .Where(r => r.sharedMaterials.Length == 1 && r.sharedMaterial != null)
                .Where(r => r.GetComponent<MeshFilter>()?.sharedMesh != null)
                .Where(r => !r.gameObject.name.StartsWith("Quad"))
                .Where(r => r.GetComponentInParent<LODGroup>() == null)
                .Where(r => !r.gameObject.name.EndsWith(CombinedSuffix) && !r.transform.parent.name.EndsWith(CombinedSuffix))
                // **상호작용 오브젝트는 굽지 않는다.** 구우면 원본 렌더러가 꺼지고 그림은 병합 당시 위치에 굳는다 —
                // 나중에 그 기계를 옮기면 콜라이더·자식(퍽·램프·점수판)만 따라가고 **보이는 몸통은 옛 자리에 남는다**.
                // 2026-09-10 하이 스트라이커에서 실제로 그렇게 됐다: 몸통은 x −885, 실제 기계는 −916 (T-254).
                .Where(r => r.GetComponentInParent<Festa.Booth.BoothInteractionTarget>() == null)
                .ToList();

            if (sources.Count < 2) { report.AppendLine($"{group.name}: 대상 {sources.Count}개 — 건너뜀"); return (sources.Count, sources.Count); }

            // (재질, 공간 셀) 로 묶는다. 셀은 렌더러 바운즈 중심 기준.
            var buckets = new Dictionary<string, List<MeshRenderer>>();
            foreach (var r in sources)
            {
                var c = r.bounds.center;
                int cx = Mathf.FloorToInt(c.x / CellSize), cz = Mathf.FloorToInt(c.z / CellSize);
                string key = $"{r.sharedMaterial.name}|{cx}|{cz}";
                if (!buckets.TryGetValue(key, out var list)) buckets[key] = list = new List<MeshRenderer>();
                list.Add(r);
            }

            var holder = new GameObject(group.name + CombinedSuffix);
            holder.transform.SetParent(group, false);
            holder.transform.position = Vector3.zero; holder.transform.rotation = Quaternion.identity; holder.transform.localScale = Vector3.one;
            GameObjectUtility.SetStaticEditorFlags(holder, StaticEditorFlags.OccludeeStatic | StaticEditorFlags.BatchingStatic);

            int produced = 0, merged = 0;
            foreach (var kv in buckets.OrderBy(k => k.Key))
            {
                var list = kv.Value;
                if (list.Count < 2) continue;   // 혼자인 것은 그대로 둔다 — 굽는 이득이 없다

                var combine = new CombineInstance[list.Count];
                int vertTotal = 0;
                for (int i = 0; i < list.Count; i++)
                {
                    var mf = list[i].GetComponent<MeshFilter>();
                    combine[i] = new CombineInstance { mesh = mf.sharedMesh, transform = list[i].localToWorldMatrix, subMeshIndex = 0 };
                    vertTotal += mf.sharedMesh.vertexCount;
                }

                var mesh = new Mesh { name = $"{group.name}_{Sanitize(kv.Key)}" };
                if (vertTotal > 65000) mesh.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
                mesh.CombineMeshes(combine, mergeSubMeshes: true, useMatrices: true);
                mesh.RecalculateBounds();
                mesh.UploadMeshData(markNoLongerReadable: true);   // 런타임 CPU 접근 없음 — 메모리 절반

                string assetPath = $"{OutDir}/{mesh.name}.asset";
                AssetDatabase.DeleteAsset(assetPath);
                AssetDatabase.CreateAsset(mesh, assetPath);

                var first = list[0];
                var go = new GameObject(mesh.name);
                go.transform.SetParent(holder.transform, false);
                go.layer = first.gameObject.layer;
                var filter = go.AddComponent<MeshFilter>(); filter.sharedMesh = mesh;
                var mr = go.AddComponent<MeshRenderer>();
                mr.sharedMaterial = first.sharedMaterial;
                mr.shadowCastingMode = first.shadowCastingMode;
                mr.receiveShadows = first.receiveShadows;
                mr.lightProbeUsage = first.lightProbeUsage;
                mr.reflectionProbeUsage = first.reflectionProbeUsage;
                mr.motionVectorGenerationMode = MotionVectorGenerationMode.Camera;
                GameObjectUtility.SetStaticEditorFlags(go, StaticEditorFlags.OccludeeStatic | StaticEditorFlags.BatchingStatic);

                foreach (var r in list) r.enabled = false;   // 원본은 끈다 — 콜라이더·Light·스크립트는 살아 있다
                produced++; merged += list.Count;
            }

            int after = sources.Count - merged + produced;
            report.AppendLine($"{group.name}: {sources.Count} → {after} (병합 메시 {produced}개, 원본 {merged}개 끔, 셀 {CellSize}u)");
            return (sources.Count, after);
        }

        /// <summary>`*_Combined` 홀더를 지우고 그 그룹의 꺼진 MeshRenderer 를 다시 켠다.</summary>
        static void Restore(Transform group, bool quiet)
        {
            var holder = group.Find(group.name + CombinedSuffix);
            if (holder == null) return;
            Object.DestroyImmediate(holder.gameObject);
            int on = 0;
            foreach (var r in group.GetComponentsInChildren<MeshRenderer>(true))
                if (!r.enabled && r.GetComponent<MeshFilter>()?.sharedMesh != null) { r.enabled = true; on++; }
            if (!quiet) Debug.Log($"[FestivalStaticCombiner] {group.name}: 복원 — 렌더러 {on}개 다시 켬");
        }

        /// <summary>`GameObject.Find("@Festival/Festival_Clutter")` 가 경로 검색에 실패한 사례가 있어 루트에서 Transform.Find 로 내려간다.</summary>
        static GameObject FindGroup(string path)
        {
            var parts = path.Split('/');
            var root = GameObject.Find(parts[0]);
            if (root == null) return null;
            Transform t = root.transform;
            for (int i = 1; i < parts.Length && t != null; i++) t = t.Find(parts[i]);
            return t != null ? t.gameObject : null;
        }

        /// <summary>System.IO 로 만든 폴더는 AssetDatabase 가 모른다 — CreateAsset 이 실패한다. 단계별로 등록한다.</summary>
        static void EnsureAssetFolder(string path)
        {
            var parts = path.Split('/');
            string cur = parts[0];
            for (int i = 1; i < parts.Length; i++)
            {
                string next = cur + "/" + parts[i];
                if (!AssetDatabase.IsValidFolder(next)) AssetDatabase.CreateFolder(cur, parts[i]);
                cur = next;
            }
        }

        static string Sanitize(string s)
        {
            var sb = new StringBuilder();
            foreach (var ch in s) sb.Append(char.IsLetterOrDigit(ch) ? ch : '_');
            return sb.ToString();
        }
    }
}
