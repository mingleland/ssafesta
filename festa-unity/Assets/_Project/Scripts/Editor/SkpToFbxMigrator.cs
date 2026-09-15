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
                    // 이진 FBX — 기본(ASCII)으로 뽑으면 11층 건물이 185 MB 가 된다(2026-09-09 1차 실행). 저장소·CI 에 실을 크기가 아니다.
                    if (AssetDatabase.LoadAssetAtPath<Object>(fbxPath) != null) AssetDatabase.DeleteAsset(fbxPath);
                    var opts = new ExportModelOptions
                    {
                        ExportFormat = ExportFormat.Binary,
                        ModelAnimIncludeOption = Include.Model,
                        ObjectPosition = ObjectPosition.Reset,   // 프리팹 변환용 — 루트 트랜스폼을 씬 인스턴스가 들고 있다
                        EmbedTextures = false,
                        KeepInstances = true,
                        ExportUnrendered = true,
                        PreserveImportSettings = false,
                        // 이름을 그대로 둔다 — 기본값(true)은 "cylinder 1" 을 "cylinder_1" 로 바꿔 씬 인스턴스의 자식 오버라이드(추가 콜라이더·위치)가
                        // ByName 매칭에서 전부 떨어졌다(2026-09-09 2차 실행: 건물 바운즈 389×271 → 306×424, MeshCollider 15 → 2).
                        UseMayaCompatibleNames = false,
                    };
                    var result = ModelExporter.ExportObjects(fbxPath, new Object[] { temp }, opts);
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

                // ③-b 콜라이더 — SketchUp 임포터는 18/138 오브젝트에만 콜라이더를 만들었다(선택적). FBX 임포터의 addCollider 는 전부
                //      아니면 없음이라, FBX 를 부모로 한 **변형 프리팹**에 .skp 프리팹과 같은 경로·같은 종류의 콜라이더를 심는다.
                var fbxModel = AssetDatabase.LoadAssetAtPath<GameObject>(fbxPath);
                if (fbxModel == null) { report.AppendLine($"{baseName}: FBX 로드 실패"); continue; }
                string variantPath = $"{FbxDir}/{baseName}_Native.prefab";
                var fbx = BuildColliderVariant(skp, fbxModel, variantPath, report);
                if (fbx == null) { report.AppendLine($"{baseName}: 변형 프리팹 생성 실패"); continue; }
                var settings = new PrefabReplacingSettings
                {
                    objectMatchMode = ObjectMatchMode.ByName,
                    prefabOverridesOptions = PrefabOverridesOptions.KeepAllPossibleOverrides,
                    logInfo = false,
                    changeRootNameToAssetName = false,
                };
                // 먼저 대상 루트를 모으고 그 다음 교체한다 — 교체가 자식 트랜스폼을 파괴하므로 순회 중 교체하면
                // MissingReferenceException(2026-09-09 1차 실행, 건물 1개 교체 뒤 중단).
                var targets = new List<GameObject>();
                foreach (var root in scene.GetRootGameObjects())
                {
                    foreach (var t in root.GetComponentsInChildren<Transform>(true))
                    {
                        var go = t.gameObject;
                        if (!PrefabUtility.IsAnyPrefabInstanceRoot(go)) continue;
                        if (PrefabUtility.GetNearestPrefabInstanceRoot(go) != go) continue;
                        var src = PrefabUtility.GetCorrespondingObjectFromOriginalSource(go);
                        if (src == null || AssetDatabase.GetAssetPath(src) != skpPath) continue;
                        targets.Add(go);
                    }
                }
                int replaced = 0;
                foreach (var go in targets)
                {
                    if (go == null) continue;
                    var before = WorldBounds(go);
                    string name = go.name;
                    // 교체 전 콜라이더 상태 스냅숏 — 경로|종류 → enabled 목록. 옛 씬은 프리팹 콜라이더 일부를 꺼 두거나(wall-rear-center)
                    // 지운(elevator-switch) 오버라이드를 갖고 있었다. 교체 뒤 이 스냅숏과 정확히 같게 맞춘다 = "완벽하게 동일".
                    var snap = new Dictionary<string, List<bool>>();
                    foreach (var c in go.GetComponentsInChildren<Collider>(true))
                    {
                        string key = (c.transform == go.transform ? "" : RelPath(c.transform, go.transform)) + "|" + c.GetType().Name;
                        if (!snap.TryGetValue(key, out var list)) snap[key] = list = new List<bool>();
                        list.Add(c.enabled);
                    }
                    var rendererSnap = SnapshotRenderers(go);
                    PrefabUtility.ReplacePrefabAssetOfPrefabInstance(go, fbx, settings, InteractionMode.AutomatedAction);
                    if (go.name != name) go.name = name;   // 루트 이름은 씬 것을 유지한다(교체가 변형 에셋 이름으로 바꿔 놓는다)
                    report.AppendLine("    " + name + ": " + RestoreRenderers(go, rendererSnap, skpPath, baseName));
                    // 교체는 .skp 프리팹의 콜라이더를 "추가 컴포넌트" 로 남기고 변형 프리팹도 같은 자리에 콜라이더를 가지므로 둘이 된다(3차: 44 → 74).
                    // 스냅숏 개수만 남기고(프리팹 쪽 우선, 남는 것은 파괴 = 제거 오버라이드), enabled 는 스냅숏 값을 그대로 쓴다.
                    int removed = 0, disabled = 0;
                    var byKey = new Dictionary<string, List<Collider>>();
                    foreach (var c in go.GetComponentsInChildren<Collider>(true))
                    {
                        string key = (c.transform == go.transform ? "" : RelPath(c.transform, go.transform)) + "|" + c.GetType().Name;
                        if (!byKey.TryGetValue(key, out var list)) byKey[key] = list = new List<Collider>();
                        list.Add(c);
                    }
                    foreach (var kv in byKey)
                    {
                        var want = snap.TryGetValue(kv.Key, out var states) ? states : new List<bool>();
                        // 프리팹(변형) 쪽 컴포넌트를 앞에, 추가 오버라이드를 뒤에
                        var ordered = new List<Collider>(kv.Value);
                        ordered.Sort((a, b) => (PrefabUtility.GetCorrespondingObjectFromSource(a) == null ? 1 : 0) - (PrefabUtility.GetCorrespondingObjectFromSource(b) == null ? 1 : 0));
                        for (int i = ordered.Count - 1; i >= want.Count; i--) { Object.DestroyImmediate(ordered[i]); removed++; ordered.RemoveAt(i); }
                        for (int i = 0; i < ordered.Count; i++) if (ordered[i].enabled != want[i]) { ordered[i].enabled = want[i]; disabled++; }
                    }
                    int missing = 0;
                    foreach (var kv in snap) if (!byKey.ContainsKey(kv.Key)) { missing++; report.AppendLine($"    {name}: 옛 콜라이더가 새 인스턴스에 없음 — {kv.Key}"); }
                    report.AppendLine($"    {name}: 콜라이더 정합 — 스냅숏 {snap.Count}자리, 중복 제거 {removed}, enabled 복원 {disabled}, 누락 {missing}");
                    var after = WorldBounds(go);
                    replaced++;
                    report.AppendLine($"  {name}: 바운즈 {before.size:F1}@{before.center:F1} → {after.size:F1}@{after.center:F1} 렌더러 {go.GetComponentsInChildren<Renderer>(true).Length}");
                }
                report.AppendLine($"{baseName}: 씬 인스턴스 {replaced}개 교체");

                // ⑤ 씬 전체의 .skp 서브에셋 참조 — 복도(Corridor_*)·외벽 셸(Shell_*)이 .skp 의 메시("Mesh floor"·"Mesh wall-flat")와 재질을 직접 쓴다.
                //    CI 에는 .skp 가 없으니 FBX 의 같은 이름 메시(정점 수 동일: floor 104·wall-flat 56)와 추출 재질로 바꾼다.
                report.AppendLine($"{baseName}: " + RemapSceneWideSkpReferences(scene, skpPath, fbxPath, baseName));
            }

            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene);
            AssetDatabase.SaveAssets();
            Debug.Log("[SkpToFbx] 완료\n" + report);
        }

        /// <summary>
        /// FBX 를 부모로 한 변형 프리팹을 만들고, .skp 프리팹의 콜라이더를 같은 경로에 같은 종류·값으로 복제한다.
        /// 자식 이름이 1:1 로 맞는지도 함께 검사해 보고한다(맞지 않으면 씬 오버라이드가 떨어진다).
        /// </summary>
        static GameObject BuildColliderVariant(GameObject skp, GameObject fbxModel, string variantPath, StringBuilder report)
        {
            var skpInst = (GameObject)PrefabUtility.InstantiatePrefab(skp);
            var fbxInst = (GameObject)PrefabUtility.InstantiatePrefab(fbxModel);
            try
            {
                // 이름 복원 — FBX 는 노드 이름이 파일 전체에서 유일해야 해서 exporter 가 중복 이름에 "_1" 등을 붙인다
                // (천장 조명 80개). 형제 순서는 그대로이므로 **인덱스 경로**로 짝을 맞춰 .skp 이름을 되돌린다. 씬 오버라이드는 이름으로 붙는다.
                int renamed = RenameByIndexPath(skpInst.transform, fbxInst.transform, report);
                report.AppendLine($"  이름 복원 {renamed}개");

                // 이름 대조
                var skpPaths = new HashSet<string>(); foreach (var t in skpInst.GetComponentsInChildren<Transform>(true)) if (t != skpInst.transform) skpPaths.Add(RelPath(t, skpInst.transform));
                var fbxPaths = new HashSet<string>(); foreach (var t in fbxInst.GetComponentsInChildren<Transform>(true)) if (t != fbxInst.transform) fbxPaths.Add(RelPath(t, fbxInst.transform));
                int onlySkp = 0, onlyFbx = 0;
                foreach (var p in skpPaths) if (!fbxPaths.Contains(p)) { onlySkp++; if (onlySkp <= 5) report.AppendLine($"    이름 불일치(skp 에만): {p}"); }
                foreach (var p in fbxPaths) if (!skpPaths.Contains(p)) { onlyFbx++; if (onlyFbx <= 5) report.AppendLine($"    이름 불일치(fbx 에만): {p}"); }
                report.AppendLine($"  자식 {skpPaths.Count} vs {fbxPaths.Count}, skp 에만 {onlySkp}, fbx 에만 {onlyFbx}");

                int copied = 0, missing = 0;
                foreach (var col in skpInst.GetComponentsInChildren<Collider>(true))
                {
                    string rel = col.transform == skpInst.transform ? "" : RelPath(col.transform, skpInst.transform);
                    var target = rel == "" ? fbxInst.transform : fbxInst.transform.Find(rel);
                    if (target == null) { missing++; report.AppendLine($"    콜라이더 대상 없음: {rel} ({col.GetType().Name})"); continue; }
                    var added = target.gameObject.AddComponent(col.GetType()) as Collider;
                    EditorUtility.CopySerialized(col, added);
                    var mc = added as MeshCollider;
                    if (mc != null)
                    {
                        // 원본 sharedMesh 는 .skp 서브에셋 — FBX 쪽 같은 오브젝트의 MeshFilter 메시로 바꾼다.
                        // 그 오브젝트에 메시가 없으면(벽 6장 — 임포터가 만든 "(combined)" 충돌 전용 메시) 원본 메시를 .asset 으로 복제해 쓴다.
                        var mf = target.GetComponent<MeshFilter>();
                        var srcMc = col as MeshCollider;
                        mc.sharedMesh = mf != null ? mf.sharedMesh : null;
                        if (mc.sharedMesh == null && srcMc != null && srcMc.sharedMesh != null)
                        {
                            const string MeshDir = FbxDir + "/ColliderMeshes";
                            EnsureFolder(MeshDir);
                            string meshPath = $"{MeshDir}/{SafeName(fbxModel.name)}__{SafeName(srcMc.sharedMesh.name)}.asset";
                            var existing = AssetDatabase.LoadAssetAtPath<Mesh>(meshPath);
                            if (existing == null) { existing = Object.Instantiate(srcMc.sharedMesh); existing.name = srcMc.sharedMesh.name; AssetDatabase.CreateAsset(existing, meshPath); }
                            mc.sharedMesh = existing;
                            report.AppendLine($"    충돌 메시 복제: {rel} ← {srcMc.sharedMesh.name} ({existing.vertexCount} 정점)");
                        }
                        if (mc.sharedMesh == null) report.AppendLine($"    MeshCollider 메시 없음: {rel}");
                    }
                    copied++;
                }
                report.AppendLine($"  콜라이더 복제 {copied}, 대상 없음 {missing}");

                var saved = PrefabUtility.SaveAsPrefabAsset(fbxInst, variantPath, out bool ok);
                return ok ? saved : null;
            }
            finally { Object.DestroyImmediate(skpInst); Object.DestroyImmediate(fbxInst); }
        }

        /// <summary>
        /// 같은 부모 아래에서 **로컬 위치·회전이 같은** 형제끼리 짝을 맞춰 fbx 쪽 이름을 skp 쪽 이름으로 되돌린다.
        /// 인덱스로 맞추면 안 된다 — exporter 가 중복 이름 형제의 순서를 바꿔 천장 조명 80개가 한 칸씩 밀렸다(4차 실행).
        /// </summary>
        static int RenameByIndexPath(Transform skp, Transform fbx, StringBuilder report)
        {
            int renamed = 0;
            if (skp.childCount != fbx.childCount) { report.AppendLine($"    자식 수 불일치: {skp.name} {skp.childCount} vs {fbx.name} {fbx.childCount}"); return 0; }
            var used = new HashSet<Transform>();
            var pairs = new List<KeyValuePair<Transform, Transform>>();
            for (int i = 0; i < skp.childCount; i++)
            {
                var a = skp.GetChild(i);
                Transform best = null; float bestD = float.MaxValue;
                for (int j = 0; j < fbx.childCount; j++)
                {
                    var b = fbx.GetChild(j); if (used.Contains(b)) continue;
                    float d = (a.localPosition - b.localPosition).magnitude + Quaternion.Angle(a.localRotation, b.localRotation) * 0.001f + (a.localScale - b.localScale).magnitude;
                    if (d < bestD) { bestD = d; best = b; }
                }
                if (best == null) continue;
                if (bestD > 0.01f) report.AppendLine($"    짝 거리 큼: {a.name} ↔ {best.name} d={bestD:F3}");
                used.Add(best); pairs.Add(new KeyValuePair<Transform, Transform>(a, best));
            }
            foreach (var kv in pairs) { if (kv.Key.name != kv.Value.name) { kv.Value.name = kv.Key.name; renamed++; } }
            foreach (var kv in pairs) renamed += RenameByIndexPath(kv.Key, kv.Value, report);
            return renamed;
        }

        /// <summary>씬 안의 모든 MeshFilter·MeshCollider·Renderer 에서 이 .skp 의 서브에셋 참조를 FBX 메시·추출 재질로 바꾼다.</summary>
        static string RemapSceneWideSkpReferences(UnityEngine.SceneManagement.Scene scene, string skpPath, string fbxPath, string baseName)
        {
            var fbxMeshes = new Dictionary<string, Mesh>();
            foreach (var o in AssetDatabase.LoadAllAssetRepresentationsAtPath(fbxPath)) { var m = o as Mesh; if (m != null && !fbxMeshes.ContainsKey(m.name)) fbxMeshes[m.name] = m; }
            System.Func<Mesh, Mesh> mapMesh = (src) =>
            {
                if (src == null || AssetDatabase.GetAssetPath(src) != skpPath) return null;
                string n = src.name.StartsWith("Mesh ") ? src.name.Substring(5) : src.name;
                return fbxMeshes.TryGetValue(n, out var m) && m.vertexCount == src.vertexCount ? m : null;
            };
            int meshes = 0, cols = 0, mats = 0, unresolved = 0; var unresolvedNames = new List<string>();
            foreach (var root in scene.GetRootGameObjects())
            {
                foreach (var mf in root.GetComponentsInChildren<MeshFilter>(true))
                {
                    if (mf.sharedMesh == null || AssetDatabase.GetAssetPath(mf.sharedMesh) != skpPath) continue;
                    var m = mapMesh(mf.sharedMesh); if (m == null) { unresolved++; unresolvedNames.Add("MeshFilter " + mf.name + ":" + mf.sharedMesh.name); continue; }
                    mf.sharedMesh = m; meshes++;
                }
                foreach (var mc in root.GetComponentsInChildren<MeshCollider>(true))
                {
                    if (mc.sharedMesh == null || AssetDatabase.GetAssetPath(mc.sharedMesh) != skpPath) continue;
                    var m = mapMesh(mc.sharedMesh); if (m == null) { unresolved++; unresolvedNames.Add("MeshCollider " + mc.name + ":" + mc.sharedMesh.name); continue; }
                    mc.sharedMesh = m; cols++;
                }
                foreach (var r in root.GetComponentsInChildren<Renderer>(true))
                {
                    var arr = r.sharedMaterials; bool changed = false;
                    for (int i = 0; i < arr.Length; i++)
                    {
                        if (arr[i] == null || AssetDatabase.GetAssetPath(arr[i]) != skpPath) continue;
                        var ext = AssetDatabase.LoadAssetAtPath<Material>($"{MatDir}/{baseName}__{SafeName(arr[i].name)}.mat");
                        if (ext == null) { unresolved++; unresolvedNames.Add("Material " + r.name + ":" + arr[i].name); continue; }
                        arr[i] = ext; changed = true; mats++;
                    }
                    if (changed) r.sharedMaterials = arr;
                }
            }
            return $"씬 전체 .skp 참조 재매핑 — 메시 {meshes}, 충돌 메시 {cols}, 재질 {mats}, 미해결 {unresolved}" + (unresolved > 0 ? " [" + string.Join("; ", unresolvedNames) + "]" : "");
        }

        /// <summary>교체 전 렌더러·오브젝트 상태 스냅숏 — 경로 → (재질, 정적 플래그, enabled, activeSelf, 로컬 트랜스폼).</summary>
        sealed class RendererSnap { public Material[] Mats; public StaticEditorFlags Flags; public bool Enabled; public bool ActiveSelf; public Vector3 Pos; public Quaternion Rot; public Vector3 Scale; }

        static Dictionary<string, RendererSnap> SnapshotRenderers(GameObject root)
        {
            var d = new Dictionary<string, RendererSnap>();
            foreach (var r in root.GetComponentsInChildren<Renderer>(true))
            {
                string key = r.transform == root.transform ? "" : RelPath(r.transform, root.transform);
                if (d.ContainsKey(key)) continue;
                d[key] = new RendererSnap { Mats = r.sharedMaterials, Flags = GameObjectUtility.GetStaticEditorFlags(r.gameObject), Enabled = r.enabled, ActiveSelf = r.gameObject.activeSelf, Pos = r.transform.localPosition, Rot = r.transform.localRotation, Scale = r.transform.localScale };
            }
            return d;
        }

        /// <summary>
        /// 스냅숏대로 되돌린다. .skp 서브에셋 재질은 추출본(<c>{base}__{name}.mat</c>)으로 바꾼다 — CI 에는 .skp 가 없으니 참조가 남으면 안 된다.
        /// 이름 매칭에서 떨어진 오버라이드(재질·정적 플래그·비활성)를 여기서 복구한다(4차 실행: 천장 조명 80개의 WorldCeilingDownlight·Occluder 플래그 유실).
        /// </summary>
        static string RestoreRenderers(GameObject root, Dictionary<string, RendererSnap> snap, string skpPath, string baseName)
        {
            int mats = 0, flags = 0, en = 0, act = 0, xf = 0, missing = 0, skpRef = 0;
            var current = new Dictionary<string, Renderer>();
            foreach (var r in root.GetComponentsInChildren<Renderer>(true)) { string key = r.transform == root.transform ? "" : RelPath(r.transform, root.transform); if (!current.ContainsKey(key)) current[key] = r; }
            foreach (var kv in snap)
            {
                if (!current.TryGetValue(kv.Key, out var r)) { missing++; continue; }
                var want = new Material[kv.Value.Mats.Length]; bool changed = want.Length != r.sharedMaterials.Length;
                for (int i = 0; i < want.Length; i++)
                {
                    var m = kv.Value.Mats[i];
                    if (m != null && AssetDatabase.GetAssetPath(m) == skpPath)
                    {
                        var ext = AssetDatabase.LoadAssetAtPath<Material>($"{MatDir}/{baseName}__{SafeName(m.name)}.mat");
                        if (ext != null) { m = ext; skpRef++; }
                    }
                    want[i] = m;
                    if (!changed && (i >= r.sharedMaterials.Length || r.sharedMaterials[i] != m)) changed = true;
                }
                if (changed) { r.sharedMaterials = want; mats++; }
                if (GameObjectUtility.GetStaticEditorFlags(r.gameObject) != kv.Value.Flags) { GameObjectUtility.SetStaticEditorFlags(r.gameObject, kv.Value.Flags); flags++; }
                if (r.enabled != kv.Value.Enabled) { r.enabled = kv.Value.Enabled; en++; }
                if (r.gameObject.activeSelf != kv.Value.ActiveSelf) { r.gameObject.SetActive(kv.Value.ActiveSelf); act++; }
                var t = r.transform;
                if ((t.localPosition - kv.Value.Pos).magnitude > 0.0005f || Quaternion.Angle(t.localRotation, kv.Value.Rot) > 0.01f || (t.localScale - kv.Value.Scale).magnitude > 0.0005f)
                { t.localPosition = kv.Value.Pos; t.localRotation = kv.Value.Rot; t.localScale = kv.Value.Scale; xf++; }
            }
            return $"렌더러 정합 — 스냅숏 {snap.Count}, 재질 복원 {mats}(skp 참조→추출본 {skpRef}), 정적 플래그 {flags}, enabled {en}, active {act}, 트랜스폼 {xf}, 누락 {missing}";
        }

        static string RelPath(Transform t, Transform root)
        {
            var s = t.name; while (t.parent != null && t.parent != root) { t = t.parent; s = t.name + "/" + s; } return s;
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
