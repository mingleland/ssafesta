// 부스 꾸미기 assetCode 정본(28행, GitLab #146 colosair 2026-09-08 회신)을 BoothObjectRegistry 에 등록한다.
// 왜 있는가: 스튜디오(FE)가 내는 (type, assetCode) 를 Unity 가 같은 값으로 받아야 사용자가 고른 외형이
// 월드에 뜬다. 전에는 FURNITURE_DEFAULT·DECORATION_DEFAULT 둘만 있어 화분을 놓으면 진열장이 섰다(QA #10).
// 프리팹은 ExpoKit 에 이미 있는 것을 **래퍼로 감싸** 쓴다(기존 Furniture.prefab 과 같은 방식) — 벤더 원본은 건드리지 않는다.
using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;
using Festa.Booth;

namespace Festa.EditorTools
{
    public static class BoothDecorRegistryBuilder
    {
        const string RegistryPath = "Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset";
        const string WrapperDir = "Assets/_Project/Prefabs/Booth/Decor";
        const string Expo = "Assets/_Project/Art/Booth/ExpoKit";

        struct Row { public BoothObjectType Type; public string Code; public string Source; public bool IsExisting; }

        /// <summary>정본 28행. Source 가 Prefabs/Booth 면 기존 프리팹(코드만 추가), ExpoKit 이면 래퍼를 새로 만든다.</summary>
        static readonly Row[] Rows =
        {
            // 타입 기본 8행 — 기존 프리팹에 canonical 코드를 덧붙인다(빈 코드 엔트리는 그대로 남아 typeDefault 역할)
            new Row { Type = BoothObjectType.SurveyKiosk,      Code = "BOOTH_KIOSK_SURVEY",   Source = "Assets/_Project/Prefabs/Booth/SurveyKiosk.prefab",      IsExisting = true },
            new Row { Type = BoothObjectType.ConsultationDesk, Code = "BOOTH_DESK_CONSULT",   Source = "Assets/_Project/Prefabs/Booth/ConsultationDesk.prefab", IsExisting = true },
            new Row { Type = BoothObjectType.VideoScreen,      Code = "BOOTH_SCREEN_VIDEO",   Source = "Assets/_Project/Prefabs/Booth/VideoScreen.prefab",      IsExisting = true },
            new Row { Type = BoothObjectType.ProjectPanel,     Code = "BOOTH_PANEL_PROJECT",  Source = "Assets/_Project/Prefabs/Booth/ProjectPanel.prefab",     IsExisting = true },
            new Row { Type = BoothObjectType.RecruitmentBoard, Code = "BOOTH_BOARD_RECRUIT",  Source = "Assets/_Project/Prefabs/Booth/RecruitmentBoard.prefab", IsExisting = true },
            new Row { Type = BoothObjectType.LikeVote,         Code = "BOOTH_STAND_LIKE",     Source = "Assets/_Project/Prefabs/Booth/LikeVote.prefab",         IsExisting = true },
            new Row { Type = BoothObjectType.Laptop,           Code = "BOOTH_DESK_LAPTOP",    Source = "Assets/_Project/Prefabs/Booth/Laptop.prefab",           IsExisting = true },
            new Row { Type = BoothObjectType.AiAgent,          Code = "BOOTH_AGENT_AI",       Source = "Assets/_Project/Prefabs/Booth/AiAgent.prefab",          IsExisting = true },
            // 레거시 교체 2행 — 같은 자산, canonical 코드. (FURNITURE_DEFAULT·DECORATION_DEFAULT 는 예약 — 저장된 layout 호환용으로 남긴다)
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_SET_TABLE_CHAIRS", Source = "Assets/_Project/Prefabs/Booth/Furniture.prefab",  IsExisting = true },
            new Row { Type = BoothObjectType.Decoration, Code = "DISP_SET_BOX_01",       Source = "Assets/_Project/Prefabs/Booth/Decoration.prefab", IsExisting = true },
            // typeDefault — 레지스트리는 **빈 코드 엔트리**를 타입 기본으로 삼는다. 후보가 여럿인데 빈 엔트리가 없으면
            // 배열 순서로 고르며 경고를 낸다(2026-09-09 플레이 실측). colosair 표의 typeDefault ✔ 두 행이 이것이다.
            new Row { Type = BoothObjectType.Furniture,  Code = "", Source = "Assets/_Project/Prefabs/Booth/Furniture.prefab",  IsExisting = true },
            new Row { Type = BoothObjectType.Decoration, Code = "", Source = "Assets/_Project/Prefabs/Booth/Decoration.prefab", IsExisting = true },
            // 신규 18행 — ExpoKit 프리팹 래퍼
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_CHAIR_01_WHITE",  Source = Expo + "/Prefabs/Furniture/Chair01_white.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_CHAIR_01_BLUE",   Source = Expo + "/Prefabs/Furniture/Chair01_blue.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_CHAIR_01_ORANGE", Source = Expo + "/Prefabs/Furniture/Chair01_orange.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_CHAIR_02_WHITE",  Source = Expo + "/Prefabs/Furniture/Chair02_White.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_COUNTER_01",      Source = Expo + "/Prefabs/Furniture/Counter01.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_COUNTER_01B",     Source = Expo + "/Prefabs/Furniture/Counter01b.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_COUNTER_02",      Source = Expo + "/Prefabs/Furniture/Counter02.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_TABLE_ROUND",     Source = Expo + "/Prefabs/Furniture/TableRound.prefab" },
            new Row { Type = BoothObjectType.Furniture,  Code = "FURN_TABLE_SQUARE",    Source = Expo + "/Prefabs/Furniture/TableSquare.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "DISP_BOX_01",          Source = Expo + "/Models/Stands/DisplayBox01.FBX" },
            new Row { Type = BoothObjectType.Decoration, Code = "DISP_STAND_PLASTIC_01", Source = Expo + "/Prefabs/Displays/StandPlastic01.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "DEVICE_TABLET",        Source = Expo + "/Prefabs/Misc/Tablet.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_PANEL_01",      Source = Expo + "/Prefabs/Panels/Panel01b.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_PANEL_02",      Source = Expo + "/Prefabs/Panels/Panel02b.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_PANEL_03",      Source = Expo + "/Prefabs/Panels/Panel03.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_TRUSS_BASE",    Source = Expo + "/Prefabs/Truss/TrussBase.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_TRUSS_VERTICAL", Source = Expo + "/Prefabs/Truss/TrussVertical.prefab" },
            new Row { Type = BoothObjectType.Decoration, Code = "STRUCT_TRUSS_HORIZONTAL_LAMP", Source = Expo + "/Prefabs/Truss/TrussHorizontal_Lamp02.prefab" },
        };

        [MenuItem("Festa/부스/장식 assetCode 28행 등록 — GitLab #146")]
        public static void Build()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[BoothDecorRegistryBuilder] 플레이 모드에서는 돌리지 않는다."); return; }
            EnsureFolder(WrapperDir);
            var report = new StringBuilder();
            var urpDefault = GraphicsSettings.currentRenderPipeline != null ? GraphicsSettings.currentRenderPipeline.defaultMaterial : null;
            if (urpDefault == null) { Debug.LogError("[BoothDecorRegistryBuilder] URP 기본 재질을 찾지 못했다 — 파이프라인 에셋 확인"); return; }

            var registry = AssetDatabase.LoadAssetAtPath<BoothObjectRegistry>(RegistryPath);
            var so = new SerializedObject(registry);
            var entries = so.FindProperty("_entries");

            int wrappers = 0, added = 0, updated = 0;
            foreach (var row in Rows)
            {
                GameObject prefab;
                if (row.IsExisting)
                {
                    prefab = AssetDatabase.LoadAssetAtPath<GameObject>(row.Source);
                    if (prefab == null) { report.AppendLine($"{row.Code}: 기존 프리팹 없음 {row.Source}"); continue; }
                }
                else
                {
                    prefab = BuildWrapper(row, urpDefault, report);
                    if (prefab == null) continue;
                    wrappers++;
                }

                // 같은 (type, code) 엔트리가 있으면 프리팹만 갱신, 없으면 추가
                int found = -1;
                for (int i = 0; i < entries.arraySize; i++)
                {
                    var e = entries.GetArrayElementAtIndex(i);
                    if ((BoothObjectType)e.FindPropertyRelative("type").enumValueIndex == row.Type &&
                        e.FindPropertyRelative("assetCode").stringValue == row.Code) { found = i; break; }
                }
                if (found < 0) { entries.InsertArrayElementAtIndex(entries.arraySize); found = entries.arraySize - 1; added++; } else updated++;
                var target = entries.GetArrayElementAtIndex(found);
                target.FindPropertyRelative("type").enumValueIndex = (int)row.Type;
                target.FindPropertyRelative("assetCode").stringValue = row.Code;
                target.FindPropertyRelative("prefab").objectReferenceValue = prefab;
            }
            so.ApplyModifiedPropertiesWithoutUndo();
            registry.Invalidate();
            EditorUtility.SetDirty(registry);

            // 기존 Decoration.prefab 의 Standard(내장) 재질도 URP 로 덮는다 — DisplayBox01Stuff 유리·플라스틱 3슬롯
            FixLegacyWrapperMaterials("Assets/_Project/Prefabs/Booth/Decoration.prefab", urpDefault, report);

            AssetDatabase.SaveAssets();
            report.Insert(0, $"[BoothDecorRegistryBuilder] 래퍼 {wrappers}개 생성, 레지스트리 엔트리 추가 {added} / 갱신 {updated} → 총 {entries.arraySize}\n");
            Debug.Log(report.ToString());
        }

        /// <summary>
        /// ExpoKit 프리팹(또는 FBX 모델)을 자식으로 품은 래퍼 프리팹. 루트는 바닥 피벗·단위 스케일.
        /// URP 가 아닌 재질(내장 Standard Default-Material)은 URP 기본 Lit 로 덮어 마젠타/미표시를 막는다(T-08).
        /// 콜라이더가 없는 것(패널·트러스·태블릿)은 렌더러 바운즈 BoxCollider 를 루트에 붙여 몸으로 통과하지 않게 한다(QA #49 와 같은 이유).
        /// </summary>
        static GameObject BuildWrapper(Row row, Material urpDefault, StringBuilder report)
        {
            var source = AssetDatabase.LoadAssetAtPath<GameObject>(row.Source);
            if (source == null) { report.AppendLine($"{row.Code}: 원본 없음 {row.Source}"); return null; }

            var root = new GameObject(row.Code);
            try
            {
                root.AddComponent<BoothRuntimeObject>();
                var child = (GameObject)PrefabUtility.InstantiatePrefab(source);
                child.transform.SetParent(root.transform, false);
                // 원본 프리팹 루트의 트랜스폼을 **그대로 둔다.** ExpoKit 패널은 루트에 -90° 회전이 있어야 서 있다
                // (FBX 축 보정). 2026-09-09 첫 생성에서 identity 로 지워 패널이 바닥에 눕혀 나왔다(플레이 실측).
                child.transform.localPosition = source.transform.localPosition;
                child.transform.localRotation = source.transform.localRotation;
                child.transform.localScale = source.transform.localScale;

                int fixedMats = 0;
                foreach (var r in child.GetComponentsInChildren<Renderer>(true))
                {
                    var mats = r.sharedMaterials; bool changed = false;
                    for (int i = 0; i < mats.Length; i++)
                        if (mats[i] == null || mats[i].shader == null || !mats[i].shader.name.StartsWith("Universal Render Pipeline")) { mats[i] = urpDefault; changed = true; fixedMats++; }
                    if (changed) r.sharedMaterials = mats;
                }

                bool addedCollider = false;
                if (root.GetComponentInChildren<Collider>(true) == null)
                {
                    var rs = root.GetComponentsInChildren<Renderer>(true);
                    if (rs.Length > 0)
                    {
                        var b = rs[0].bounds; foreach (var r in rs) b.Encapsulate(r.bounds);
                        var box = root.AddComponent<BoxCollider>();
                        box.center = b.center; box.size = b.size;   // 루트가 원점·단위 스케일이라 월드 = 로컬
                        addedCollider = true;
                    }
                }

                string path = $"{WrapperDir}/{row.Code}.prefab";
                var saved = PrefabUtility.SaveAsPrefabAsset(root, path, out bool ok);
                if (!ok) { report.AppendLine($"{row.Code}: 프리팹 저장 실패"); return null; }
                report.AppendLine($"{row.Code}: ← {System.IO.Path.GetFileName(row.Source)} (URP 재질 덮음 {fixedMats}, 콜라이더 추가 {(addedCollider ? "O" : "X")})");
                return saved;
            }
            finally { Object.DestroyImmediate(root); }
        }

        static void FixLegacyWrapperMaterials(string prefabPath, Material urpDefault, StringBuilder report)
        {
            var contents = PrefabUtility.LoadPrefabContents(prefabPath);
            try
            {
                int fixedMats = 0;
                foreach (var r in contents.GetComponentsInChildren<Renderer>(true))
                {
                    var mats = r.sharedMaterials; bool changed = false;
                    for (int i = 0; i < mats.Length; i++)
                        if (mats[i] == null || mats[i].shader == null || !mats[i].shader.name.StartsWith("Universal Render Pipeline")) { mats[i] = urpDefault; changed = true; fixedMats++; }
                    if (changed) r.sharedMaterials = mats;
                }
                if (fixedMats > 0) { PrefabUtility.SaveAsPrefabAsset(contents, prefabPath); report.AppendLine($"{System.IO.Path.GetFileName(prefabPath)}: 내장 Standard 재질 {fixedMats}슬롯 → URP 기본"); }
            }
            finally { PrefabUtility.UnloadPrefabContents(contents); }
        }

        static void EnsureFolder(string path)
        {
            var parts = path.Split('/'); string cur = parts[0];
            for (int i = 1; i < parts.Length; i++) { string next = cur + "/" + parts[i]; if (!AssetDatabase.IsValidFolder(next)) AssetDatabase.CreateFolder(cur, parts[i]); cur = next; }
        }
    }
}
