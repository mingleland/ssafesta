// SketchUp(.skp) 모델을 FBX 로 이관하고 씬의 프리팹 인스턴스를 재연결한다 — GitLab #155.
// 왜 있는가: Unity 의 SketchUp 임포터는 Windows·macOS 전용이다. Linux CI(linux-docker 에이전트)에서는 .skp 가
// 임포트되지 않아 main.unity 의 인스턴스 4개(11층 건물 1 + 엘리베이터 3)가 Missing Prefab 이 되고,
// EditMode 무결성 테스트가 떨어지며 CI 산출물에는 건물이 통째로 빠진다(2026-09-09 실측·회신).
// 절차: ① .skp 내장 재질을 .mat 로 추출 ② FBX 내보내기(com.unity.formats.fbx) ③ FBX 재질을 이름으로 ①에 재매핑
//       ④ 씬 인스턴스의 원본 프리팹을 FBX 로 교체(이름 매칭, 오버라이드 보존) ⑤ 바운즈 대조 로그.
// 오클루전 재베이크와 시각 대조는 이 메뉴 밖에서 따로 한다(T-217 계열 재발 감시).
using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEditor.Formats.Fbx.Exporter;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    public static class SkpToFbxMigrator
    {
        const string FbxDir = "Assets/_Project/Models/Fbx";
        const string MatDir = "Assets/_Project/Models/Fbx/Materials";

        static readonly string[] Sources =
        {
            "Assets/_Project/Models/11th-0821-complete textures (without elevator).skp",
            "Assets/_Project/Models/elevator with door, switch.skp",
        };

        [MenuItem("Festa/모델/SketchUp → FBX 이관 + 씬 재연결 — GitLab #155")]
        public static void Migrate()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[SkpToFbx] 플레이 모드에서는 돌리지 않는다."); return; }
            EnsureFolder(FbxDir); EnsureFolder(MatDir);
            var report = new StringBuilder();
            var scene = EditorSceneManager.GetActiveScene();
            if (scene.path != "Assets/_Project/Scenes/main.unity") { Debug.LogError("[SkpToFbx] main.unity 를 열고 돌린다. 지금: " + scene.path); return; }

            foreach (var skpPath in Sources)
            {
                var skp = AssetDatabase.LoadAssetAtPath<GameObject>(skpPath);
                if (skp == null) { report.AppendLine($"원본 없음: {skpPath}"); continue; }
                string baseName = SafeName(Path.GetFileNameWithoutExtension(skpPath));

                // ① 내장 재질 추출 — FBX 재질 재매핑의 대상. 이미 있으면 재사용.
                var extracted = new Dictionary<string, Material>();
                foreach (var sub in AssetDatabase.LoadAllAssetRepresentationsAtPath(skpPath))
                {
                    var mat = sub as Material; if (mat == null) continue;
                    string matPath = $"{MatDir}/{baseName}__{SafeName(mat.name)}.mat";
                    var existing = AssetDatabase.LoadAssetAtPath<Material>(matPath);
                    if (existing == null)
                    {
                        var copy = new Material(mat);
                        AssetDatabase.CreateAsset(copy, matPath);
                        existing = copy;
                    }
                    extracted[mat.name] = existing;
                }
                report.AppendLine($"{baseName}: 재질 {extracted.Count}개 → {MatDir}");

                // ② FBX 내보내기 — 임시 인스턴스를 완전히 언팩해서 내보낸다(프리팹 링크가 남으면 exporter 가 프리팹 자체를 참조).
                var temp = (GameObject)PrefabUtility.InstantiatePrefab(skp);
                string fbxPath;
                try
                {
                    PrefabUtility.UnpackPrefabInstance(temp, PrefabUnpackMode.Completely, InteractionMode.AutomatedAction);
                    temp.name = baseName;
                    fbxPath = $"{FbxDir}/{baseName}.fbx";
                    var result = ModelExporter.ExportObject(fbxPath, temp);
                    if (string.IsNullOrEmpty(result)) { report.AppendLine($"{baseName}: FBX 내보내기 실패"); continue; }
                }
                finally { Object.DestroyImmediate(temp); }
                AssetDatabase.ImportAsset(fbxPath, ImportAssetOptions.ForceSynchronousImport);

                // ③ 재질 재매핑 — 이름으로 ① 에 연결. 스케일은 exporter 가 Unity 단위 그대로 쓰므로 1.
                var importer = AssetImporter.GetAtPath(fbxPath) as ModelImporter;
                if (importer != null)
                {
                    importer.materialImportMode = ModelImporterMaterialImportMode.ImportStandard;
                    importer.materialLocation = ModelImporterMaterialLocation.InPrefab;
                    importer.isReadable = false;
                    importer.meshCompression = ModelImporterMeshCompression.Off;   // 원본과 정점 동일하게 — 압축은 별도 판단
                    importer.importCameras = false; importer.importLights = false;
                    int remapped = 0;
                    foreach (var kv in extracted)
                    {
                        var id = new AssetImporter.SourceAssetIdentifier(typeof(Material), kv.Key);
                        importer.AddRemap(id, kv.Value); remapped++;
                    }
                    importer.SaveAndReimport();
                    report.AppendLine($"{baseName}: FBX {fbxPath}, 재질 재매핑 {remapped}");
                }

                // ④ 씬 인스턴스 교체 — 원본이 이 .skp 인 인스턴스 루트를 전부 찾아 FBX 프리팹으로 바꾼다.
                var fbx = AssetDatabase.LoadAssetAtPath<GameObject>(fbxPath);
                if (fbx == null) { report.AppendLine($"{baseName}: FBX 로드 실패"); continue; }
                var settings = new PrefabReplacingSettings
                {
                    objectMatchMode = ObjectMatchMode.ByName,
                    prefabOverridesOptions = PrefabOverridesOptions.KeepAllPossibleOverrides,
                    logInfo = false,
                    changeRootNameToAssetName = false,
                };
                int replaced = 0;
                foreach (var root in scene.GetRootGameObjects())
                {
                    foreach (var t in root.GetComponentsInChildren<Transform>(true))
                    {
                        var go = t.gameObject;
                        if (!PrefabUtility.IsAnyPrefabInstanceRoot(go)) continue;
                        if (PrefabUtility.GetNearestPrefabInstanceRoot(go) != go) continue;
                        var src = PrefabUtility.GetCorrespondingObjectFromOriginalSource(go);
                        if (src == null || AssetDatabase.GetAssetPath(src) != skpPath) continue;

                        var before = WorldBounds(go);
                        string name = go.name;
                        PrefabUtility.ReplacePrefabAssetOfPrefabInstance(go, fbx, settings, InteractionMode.AutomatedAction);
                        var after = WorldBounds(go);
                        replaced++;
                        report.AppendLine($"  {name}: 바운즈 {before.size:F1}@{before.center:F1} → {after.size:F1}@{after.center:F1} 렌더러 {go.GetComponentsInChildren<Renderer>(true).Length}");
                    }
                }
                report.AppendLine($"{baseName}: 씬 인스턴스 {replaced}개 교체");
            }

            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene);
            AssetDatabase.SaveAssets();
            Debug.Log("[SkpToFbx] 완료\n" + report);
        }

        static Bounds WorldBounds(GameObject go)
        {
            var rs = go.GetComponentsInChildren<Renderer>(true);
            if (rs.Length == 0) return new Bounds(go.transform.position, Vector3.zero);
            var b = rs[0].bounds; foreach (var r in rs) b.Encapsulate(r.bounds); return b;
        }

        static string SafeName(string s)
        {
            foreach (var c in Path.GetInvalidFileNameChars()) s = s.Replace(c, '_');
            return s.Replace(' ', '_').Replace(',', '_').Replace('(', '_').Replace(')', '_');
        }

        static void EnsureFolder(string path)
        {
            var parts = path.Split('/'); string cur = parts[0];
            for (int i = 1; i < parts.Length; i++) { string next = cur + "/" + parts[i]; if (!AssetDatabase.IsValidFolder(next)) AssetDatabase.CreateFolder(cur, parts[i]); cur = next; }
        }
    }
}
