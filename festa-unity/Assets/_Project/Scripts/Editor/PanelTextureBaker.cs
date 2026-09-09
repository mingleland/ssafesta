// 11층 안내 판넬(SketchUp 글자 메시)을 텍스처 한 장으로 굽고 Quad 로 바꾼다 — 사용자 지시 2026-09-09 (2번 사진).
// 왜 있는가: 판넬의 본문 글자가 3D 글리프 메시(수천 개의 작은 삼각형)라 화면에서 얼룩처럼 깨진다. 임포터를 바꿔도
// 지오메트리가 그런 것이니, 판넬 정면을 직교 카메라로 고해상도(2048², MSAA 8) 렌더해 PNG 로 저장하고, 같은 자리에 Quad 하나를
// 세운 뒤 원본 렌더러를 끈다(콜라이더는 남긴다). 밉맵·비등방 8 이 걸린 텍스처는 멀리서도 뭉개지지 않는다(T-242 규칙과 같다).
// 배경은 투명(alpha 0)으로 찍어 판넬 밖 영역은 벽이 그대로 보인다.
using System.IO;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    public static class PanelTextureBaker
    {
        const string OutDir = "Assets/_Project/Art/World/Textures/Panels";
        const int Resolution = 2048;
        const string BakeLayerName = "Water";   // 씬에서 안 쓰는 내장 레이어를 임시 컬링 레이어로 빌린다(굽는 동안만)
        const string RootName = "@BakedPanels";

        static readonly string[] Targets =
        {
            "ssafy-11th-room/content-introduction",
            "ssafy-11th-room/content-education",
        };

        /// <summary>
        /// 글자 덧대기 — SketchUp 3D 글자 메시가 임포트에서 깨져(삼각형 유실, 압축 무관 — 2026-09-09 2차 굽기로 확인) 읽을 수 없다.
        /// 깨진 영역을 판넬색 패치로 덮고 그 위에 TMP(Noto Sans KR Bold)로 다시 쓴 뒤 굽는다. 좌표는 판넬 정면 정규화(0~1, 좌상단 원점).
        /// 원문은 사용자 확인 대상 — 연혁 5줄은 연도만 두고 설명을 비워 뒀다(원문 받으면 채운다).
        /// </summary>
        sealed class TextPatch { public string Target; public Rect Area; public string Text; public float FontSize; public TMPro.TextAlignmentOptions Align; public Color Color; }
        static readonly Color PanelColor = new Color(0.80f, 0.80f, 0.86f);
        static readonly Color InkColor = new Color(0.22f, 0.24f, 0.32f);
        static readonly Color BlueColor = new Color(0.18f, 0.32f, 0.72f);
        static readonly TextPatch[] Patches =
        {
            new TextPatch { Target = "content-introduction", Area = new Rect(0.07f, 0.44f, 0.40f, 0.12f), FontSize = 7f, Align = TMPro.TextAlignmentOptions.TopLeft, Color = InkColor,
                Text = "<color=#2E52B8><b>삼성청년SW·AI아카데미(SSAFY)</b></color>는 삼성전자의 사회공헌 비전인 '함께가요 미래로! Enabling People'의 일환으로, 청년의 취업 경쟁력을 높이고 국가 소프트웨어·AI 경쟁력을 강화하기 위해 2018년 12월 출범한 청년 SW 교육 프로그램입니다.\n\n1년 1,600시간의 몰입형 교육과 취업 지원을 통해 IT 산업이 요구하는 실무형 인재를 키웁니다." },
            new TextPatch { Target = "content-introduction", Area = new Rect(0.07f, 0.565f, 0.42f, 0.07f), FontSize = 7f, Align = TMPro.TextAlignmentOptions.TopLeft, Color = InkColor,
                Text = "<b>전국 5개 캠퍼스</b>  서울 · 대전 · 광주 · 구미 · 부울경" },
            new TextPatch { Target = "content-introduction", Area = new Rect(0.52f, 0.44f, 0.44f, 0.175f), FontSize = 7f, Align = TMPro.TextAlignmentOptions.TopLeft, Color = InkColor,
                Text = "<b>2018</b>  삼성청년SW아카데미 출범 · 1기 입과\n<b>2021</b>  \n<b>2022</b>  \n<b>2024</b>  \n<b>2025</b>  삼성청년SW·AI아카데미로 확대" },
            new TextPatch { Target = "content-introduction", Area = new Rect(0.52f, 0.81f, 0.30f, 0.04f), FontSize = 9f, Align = TMPro.TextAlignmentOptions.MidlineLeft, Color = BlueColor,
                Text = "함께가요 미래로!" },
        };

        [MenuItem("Festa/모델/11층 안내 판넬 텍스처로 굽기 (Quad 교체)")]
        public static void Bake()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[PanelBake] 플레이 모드에서는 돌리지 않는다."); return; }
            EnsureFolder(OutDir);
            var report = new StringBuilder();
            var scene = EditorSceneManager.GetActiveScene();
            var root = GameObject.Find(RootName) ?? new GameObject(RootName);
            int layer = LayerMask.NameToLayer(BakeLayerName);

            foreach (var path in Targets)
            {
                var target = FindByPath(path);
                if (target == null) { report.AppendLine($"{path}: 없음"); continue; }
                var renderers = target.GetComponentsInChildren<Renderer>(true);
                if (renderers.Length == 0) { report.AppendLine($"{path}: 렌더러 없음"); continue; }

                // 정면 방향 — 판넬은 벽에 붙어 있고 방 안쪽(+Z, 로비 쪽)을 본다. 바운즈의 얇은 축이 두께다.
                Bounds wb = renderers[0].bounds; foreach (var r in renderers) wb.Encapsulate(r.bounds);
                Vector3 normal = wb.size.z <= wb.size.x ? Vector3.forward : Vector3.right;   // 얇은 축이 z 면 +z 를 본다
                // 로비 중심 쪽을 향하도록 부호 결정 (방 중심 대략 (-93, 25, -226))
                var roomCenter = new Vector3(-93f, 25f, -226f);
                if (Vector3.Dot(roomCenter - wb.center, normal) < 0) normal = -normal;
                Vector3 up = Vector3.up;
                Vector3 right = Vector3.Cross(normal, up).normalized;   // 카메라가 -normal 을 보므로 화면 오른쪽 = normal×up (1차 굽기에서 좌우가 뒤집혔다)
                float halfW = Mathf.Abs(Vector3.Dot(wb.extents, right));
                float halfH = wb.extents.y;
                // 판넬 크기 그대로(여백 없음) — 여백을 두면 투명 배경이 필요한데 URP 카메라 클리어가 alpha 를 1 로 남겨 검은 테두리가 생겼다(1차 실행).
                // 텍스처는 2048² 에 가로로 늘려 찍고 Quad 를 (w,h) 로 세워 되돌린다.
                float aspect = halfW / halfH;
                float depth = Mathf.Abs(Vector3.Dot(wb.extents, normal));

                // 굽는 동안 대상만 보이게 레이어 이동
                var saved = new System.Collections.Generic.Dictionary<GameObject, int>();
                foreach (var r in renderers) { saved[r.gameObject] = r.gameObject.layer; r.gameObject.layer = layer; }

                // 글자 덧대기 — 패치 쿼드(판넬색) + TMP 텍스트를 정면 살짝 앞에 세운다(굽고 나면 지운다)
                var patchRoot = new GameObject("__PanelBakePatches"); patchRoot.layer = layer;
                var faceCenter = wb.center + normal * Mathf.Abs(Vector3.Dot(wb.extents, normal));
                var font = AssetDatabase.LoadAssetAtPath<TMPro.TMP_FontAsset>("Assets/_Project/Resources/Fonts/NotoSansKRBold_SDF.asset");
                int patched = 0;
                foreach (var p in Patches)
                {
                    if (p.Target != target.name) continue;
                    // 정규화 → 월드: x 오른쪽 = right, y 아래 = -up. 좌상단 = faceCenter - right*halfW + up*halfH
                    float w = p.Area.width * halfW * 2f, h = p.Area.height * halfH * 2f;
                    var center = faceCenter - right * halfW + up * halfH + right * ((p.Area.x + p.Area.width * 0.5f) * halfW * 2f) - up * ((p.Area.y + p.Area.height * 0.5f) * halfH * 2f);
                    var bg = GameObject.CreatePrimitive(PrimitiveType.Quad); Object.DestroyImmediate(bg.GetComponent<Collider>());
                    bg.name = "patch"; bg.layer = layer; bg.transform.SetParent(patchRoot.transform, true);
                    bg.transform.position = center + normal * 0.08f; bg.transform.rotation = Quaternion.LookRotation(-normal, up); bg.transform.localScale = new Vector3(w, h, 1f);
                    // 패치 재질 = 판넬 몸체 재질(_defaultMat) 그대로 — 같은 조명을 받아 색이 맞는다. 없으면 Unlit 판넬색
                    Material bgMat = null; foreach (var rr in renderers) { foreach (var mm in rr.sharedMaterials) if (mm != null && mm.name.Contains("_defaultMat")) { bgMat = mm; break; } if (bgMat != null) break; }
                    if (bgMat == null) { bgMat = new Material(Shader.Find("Universal Render Pipeline/Unlit")); bgMat.SetColor("_BaseColor", PanelColor); } bg.GetComponent<MeshRenderer>().sharedMaterial = bgMat;
                    var tgo = new GameObject("text"); tgo.layer = layer; tgo.transform.SetParent(patchRoot.transform, true);
                    tgo.transform.position = center + normal * 0.12f; tgo.transform.rotation = Quaternion.LookRotation(-normal, up);
                    var tmp = tgo.AddComponent<TMPro.TextMeshPro>();
                    tmp.rectTransform.sizeDelta = new Vector2(w * 0.94f, h * 0.9f);
                    if (font != null) tmp.font = font;
                    tmp.text = p.Text; tmp.enableAutoSizing = true; tmp.fontSizeMin = 2f; tmp.fontSizeMax = p.FontSize; tmp.fontSize = p.FontSize;   // 상자 안에 맞춰 줄인다 — 3차 굽기에서 글자가 사진 위로 넘쳤다 tmp.alignment = p.Align; tmp.color = p.Color; tmp.enableWordWrapping = true; tmp.richText = true;
                    tmp.ForceMeshUpdate();
                    patched++;
                }

                var camGo = new GameObject("__PanelBakeCam");
                Texture2D tex = null;
                try
                {
                    var cam = camGo.AddComponent<Camera>();
                    cam.orthographic = true; cam.orthographicSize = halfH; cam.aspect = aspect;
                    cam.nearClipPlane = 0.5f; cam.farClipPlane = depth * 2f + 60f;
                    cam.clearFlags = CameraClearFlags.SolidColor; cam.backgroundColor = new Color(0, 0, 0, 0);
                    cam.cullingMask = 1 << layer; cam.allowMSAA = true; cam.allowHDR = false;
                    cam.transform.position = wb.center + normal * (depth + 30f);
                    cam.transform.rotation = Quaternion.LookRotation(-normal, up);
                    var rt = new RenderTexture(Resolution, Resolution, 24, RenderTextureFormat.ARGB32) { antiAliasing = 8 };
                    cam.targetTexture = rt; cam.Render();
                    var prev = RenderTexture.active; RenderTexture.active = rt;
                    tex = new Texture2D(Resolution, Resolution, TextureFormat.RGBA32, false);
                    tex.ReadPixels(new Rect(0, 0, Resolution, Resolution), 0, 0); tex.Apply();
                    RenderTexture.active = prev; cam.targetTexture = null; rt.Release();
                }
                finally
                {
                    Object.DestroyImmediate(camGo);
                    Object.DestroyImmediate(patchRoot);
                    foreach (var kv in saved) kv.Key.layer = kv.Value;
                }

                string safe = target.name.Replace(' ', '_');
                string pngPath = $"{OutDir}/{safe}_baked.png";
                File.WriteAllBytes(pngPath, tex.EncodeToPNG());
                Object.DestroyImmediate(tex);
                AssetDatabase.ImportAsset(pngPath, ImportAssetOptions.ForceSynchronousImport);
                var ti = AssetImporter.GetAtPath(pngPath) as TextureImporter;
                if (ti != null)
                {
                    ti.textureType = TextureImporterType.Default; ti.sRGBTexture = true; ti.alphaIsTransparency = true;
                    ti.mipmapEnabled = true; ti.filterMode = FilterMode.Trilinear; ti.anisoLevel = 8; ti.wrapMode = TextureWrapMode.Clamp;
                    ti.maxTextureSize = Resolution; ti.textureCompression = TextureImporterCompression.Compressed; ti.crunchedCompression = false;
                    ti.SaveAndReimport();
                }
                var texAsset = AssetDatabase.LoadAssetAtPath<Texture2D>(pngPath);

                // Quad — 판넬 정면 살짝 앞(0.05u)에 같은 크기로. Unlit 에 알파 클립: 렌더 결과에 이미 조명이 들어 있다.
                string matPath = $"{OutDir}/{safe}_baked.mat";
                var mat = AssetDatabase.LoadAssetAtPath<Material>(matPath);
                if (mat == null) { mat = new Material(Shader.Find("Universal Render Pipeline/Unlit")); AssetDatabase.CreateAsset(mat, matPath); }
                mat.SetTexture("_BaseMap", texAsset); mat.SetColor("_BaseColor", Color.white);
                mat.SetFloat("_AlphaClip", 0f); mat.DisableKeyword("_ALPHATEST_ON");
                EditorUtility.SetDirty(mat);

                string quadName = safe + "_Baked";
                var old = root.transform.Find(quadName); if (old != null) Object.DestroyImmediate(old.gameObject);
                var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
                Object.DestroyImmediate(quad.GetComponent<Collider>());
                quad.name = quadName; quad.transform.SetParent(root.transform, true);
                quad.transform.position = wb.center + normal * (depth + 0.05f);
                quad.transform.rotation = Quaternion.LookRotation(-normal, up);   // Quad 정면은 -Z 를 본다 → 카메라와 같은 방향으로 놓으면 관람자를 본다
                quad.transform.localScale = new Vector3(halfW * 2f, halfH * 2f, 1f);
                quad.GetComponent<MeshRenderer>().sharedMaterial = mat;
                quad.GetComponent<MeshRenderer>().shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                GameObjectUtility.SetStaticEditorFlags(quad, StaticEditorFlags.BatchingStatic | StaticEditorFlags.OccludeeStatic);

                int disabled = 0; foreach (var r in renderers) { if (r.enabled) { r.enabled = false; disabled++; } }
                report.AppendLine($"{path}: 렌더러 {renderers.Length}개 → 텍스처 {pngPath} ({Resolution}²), Quad {quad.name} 크기 {halfW * 2f:F1}×{halfH * 2f:F1} 법선 {normal}, 글자 패치 {patched}, 원본 렌더러 {disabled}개 끔");
            }

            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene);
            AssetDatabase.SaveAssets();
            Debug.Log("[PanelBake] 완료\n" + report);
        }

        [MenuItem("Festa/모델/11층 안내 판넬 굽기 되돌리기")]
        public static void Revert()
        {
            var root = GameObject.Find(RootName); if (root != null) Object.DestroyImmediate(root);
            int on = 0;
            foreach (var path in Targets) { var t = FindByPath(path); if (t == null) continue; foreach (var r in t.GetComponentsInChildren<Renderer>(true)) { if (!r.enabled) { r.enabled = true; on++; } } }
            EditorSceneManager.MarkSceneDirty(EditorSceneManager.GetActiveScene());
            Debug.Log($"[PanelBake] 되돌림 — 렌더러 {on}개 켬, {RootName} 제거");
        }

        static GameObject FindByPath(string path)
        {
            var parts = path.Split('/');
            foreach (var t in Object.FindObjectsByType<Transform>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (t.name != parts[parts.Length - 1]) continue;
                var p = t; bool ok = true;
                for (int i = parts.Length - 2; i >= 0 && ok; i--) { p = p.parent; ok = p != null && p.name == parts[i]; }
                if (ok) return t.gameObject;
            }
            return null;
        }

        static void EnsureFolder(string path)
        {
            var parts = path.Split('/'); string cur = parts[0];
            for (int i = 1; i < parts.Length; i++) { string next = cur + "/" + parts[i]; if (!AssetDatabase.IsValidFolder(next)) AssetDatabase.CreateFolder(cur, parts[i]); cur = next; }
        }
    }
}
