// 11층 SSAFY 안내 판넬의 글자를 TextMeshPro 로 다시 세운다 — 사용자 지시 2026-09-11 (실물 사진 기준).
//
// 왜 있는가. 판넬 글자는 SketchUp 에서 3D 글리프 메시로 들어왔는데 FBX 단계에서 삼각형이 유실돼
// 작은 글자가 뭉개진다. 임포터 설정(weld/optimize/UInt32)을 바꿔 재임포트해도 메시가 한 개도 바뀌지
// 않는 것으로 원본 결함임을 확인했다. 큰 제목은 멀쩡하고 작은 본문만 깨지는 것도 같은 이유다.
//
// 텍스처로 구워 덮는 방법(PanelTextureBaker)도 있었지만, 그러면 에디터에서 글자를 고칠 수 없고
// 판 하나가 앞에 더 서서 충돌·유지보수가 나빠진다. 여기서는 **씬에 실제 TMP 오브젝트로** 세운다 —
// 에디터에서 보이고, 클릭해서 고칠 수 있고, 거리와 무관하게 선명하다.
//
// 좌표는 판넬 정면을 정규화(0~1, 좌상단 원점)해서 적는다. 실물 사진과 맞추려면 이 표만 고치면 된다.
using System.Collections.Generic;
using TMPro;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    public static class FestaPanelTextBuilder
    {
        const string RootName = "@PanelText";
        const string PanelName = "content-introduction";

        /// <summary>글자를 얹을 기준 면. 판넬 표면(z=-324.956)보다 이만큼 앞에 세운다.</summary>
        const float Lift = 0.6f;

        /// <summary>본문 색 — 완전 검정은 실내광에서 먹어 보인다.</summary>
        static readonly Color Ink = new(0.09f, 0.10f, 0.12f, 1f);
        static readonly Color Blue = new(0.16f, 0.28f, 0.60f, 1f);
        static readonly Color Gray = new(0.32f, 0.34f, 0.38f, 1f);

        struct Block
        {
            public string Name, Text;
            public float X, Y, W;        // 정규화 좌표 (좌상단 원점), W 는 글상자 너비
            public float Size;           // 월드 글자 크기
            public Color Color;
            public TextAlignmentOptions Align;
            public FontStyles Style;
            public float LineSpacing;
        }

        // 실물 사진(2026-09-11) 기준. 사진은 건드리지 않고 글자만 얹는다.
        static readonly Block[] Blocks =
        {
            // ── 왼쪽: SSAFY 소개 ───────────────────────────────────────
            new() { Name="Head_Intro", Text="SSAFY 소개", X=0.055f, Y=0.055f, W=0.36f, Size=1.55f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, Style=FontStyles.Bold },

            new() { Name="Body_Intro", X=0.055f, Y=0.425f, W=0.405f, Size=0.455f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, LineSpacing=1.547f,
                    Text="삼성청년SW·AI아카데미 ( <color=#2947A0><b>SSAFY</b></color> ) 는 삼성전자의\n" +
                         "사회공헌 비전인 <color=#2947A0><b>\"함께가요 미래로!</b></color> <color=#2947A0><b>Enabling People\"</b></color> 의 \n" +
                         "취지에 따라 삼성의 SW 교육경험과 고용노동부의 취업지원 노하우를 바탕으로\n" +
                         "취업 준비생에게 SW·AI 역량 향상 교육 및 다양한 취업지원 서비스를 제공하여 \n" +
                         "취업에 성공하도록 지원하는 프로그램입니다." },

            new() { Name="Head_Facility", Text="시설현황", X=0.055f, Y=0.572f, W=0.30f, Size=0.54f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, Style=FontStyles.Bold },

            // 사진 바로 위 한 줄이라 두 줄로 끊으면 둘째 줄이 사진에 가린다 — 한 줄로 줄여 적는다.
            new() { Name="Body_Facility", Text="SSAFY 캠퍼스는 서울·대전·광주·구미·부울경 5개 지역에 있습니다.",
                    X=0.055f, Y=0.601f, W=0.44f, Size=0.40f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft },

            // ── 오른쪽: SSAFY 연혁 ─────────────────────────────────────
            new() { Name="Head_History", Text="SSAFY 연혁", X=0.545f, Y=0.055f, W=0.36f, Size=1.55f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, Style=FontStyles.Bold },

            new() { Name="Cap_History_Top", Text="'25. 4월 SSAFY 자문위원 정기회의",
                    X=0.545f, Y=0.118f, W=0.38f, Size=0.52f,
                    Color=Gray, Align=TextAlignmentOptions.TopLeft },

            new() { Name="History_Years", X=0.545f, Y=0.400f, W=0.085f, Size=0.68f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, LineSpacing=1.266f,
                    Text="20<b>18</b>\n20<b>21</b>\n20<b>23</b>\n20<b>25</b>\n20<b>26</b>" },

            new() { Name="History_Items", X=0.628f, Y=0.406f, W=0.325f, Size=0.465f,
                    Color=Ink, Align=TextAlignmentOptions.TopLeft, LineSpacing=0.756f,
                    Text="○ 12.10  SSAFY 1기 입학식 및 개소식\n" +
                         "○ 12.20  고용노동부 MOU 체결\n\n" +
                         "○ 07.09  부울경 캠퍼스 개소식\n\n" +
                         "○ 06.26  5대 은행 MOU 체결\n\n" +
                         "○ 06.24  고용노동부 MOU 연장 체결\n\n" +
                         "○ 06.30  SSAFY 14기 수료식" },

            new() { Name="Slogan_Kor", Text="함께가요 미래로!", X=0.560f, Y=0.868f, W=0.36f, Size=0.55f,
                    Color=Blue, Align=TextAlignmentOptions.TopLeft, Style=FontStyles.Bold },

            new() { Name="Slogan_Eng", Text="Enabling People", X=0.545f, Y=0.898f, W=0.42f, Size=1.02f,
                    Color=Blue, Align=TextAlignmentOptions.TopLeft, Style=FontStyles.Bold },
        };

        [MenuItem("Festa/World/11층 판넬 글자 다시 세우기 (TMP)")]
        public static void Build()
        {
            var panel = FindPanel();
            if (panel == null) { Debug.LogError($"[PanelText] {PanelName} 을 씬에서 찾지 못했다."); return; }

            // 깨진 3D 글자는 통째로 내린다 — 사진·판 본체는 그대로 둔다.
            var combined = panel.transform.Find(PanelName + " (combined)");
            if (combined != null)
            {
                var cr = combined.GetComponent<Renderer>();
                if (cr != null && cr.enabled) { Undo.RecordObject(cr, "hide broken text"); cr.enabled = false; }
            }

            // 이전 실행분을 전부 지운다 — 판넬 자식으로 붙이던 시절 것도 함께.
            for (int i = panel.transform.childCount - 1; i >= 0; i--)
            {
                var c = panel.transform.GetChild(i);
                if (c.name == RootName) Undo.DestroyObjectImmediate(c.gameObject);
            }
            while (true)
            {
                var stray = GameObject.Find(RootName);
                if (stray == null) break;
                Undo.DestroyObjectImmediate(stray);
            }

            var root = new GameObject(RootName);
            Undo.RegisterCreatedObjectUndo(root, "panel text");
            // **씬 루트에 둔다(부모 없음).** 판넬은 Y축 180° 로 놓여 있어서 자식으로 붙이면 그 회전을
            // 물려받아 글자가 뒤집힌다. 월드 좌표로 직접 놓고 방향도 여기서 정한다.

            // 글자를 얹을 사각형 — 판넬 앞면에서 글자 영역만 쓴다(사진 위를 덮지 않게 정규화 좌표로 배치).
            var area = TextArea(panel, out float faceZ);
            var font = LoadFont();

            foreach (var b in Blocks) Create(root.transform, b, area, faceZ, font);

            EditorSceneManagerSave();
            Debug.Log($"[PanelText] {Blocks.Length}개 글상자를 세웠다. 영역 {area.min} ~ {area.max}, z={faceZ + Lift:F2}");
            Selection.activeGameObject = root;
        }

        static void Create(Transform parent, Block b, Bounds area, float faceZ, TMP_FontAsset font)
        {
            var go = new GameObject(b.Name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            var tmp = go.AddComponent<TextMeshPro>();

            if (font != null) tmp.font = font;
            tmp.text = b.Text;
            tmp.fontSize = b.Size * 10f;          // TMP 는 포인트 단위 — 10 이 월드 1 유닛이다
            tmp.color = b.Color;
            tmp.fontStyle = b.Style;
            tmp.alignment = b.Align == 0 ? TextAlignmentOptions.TopLeft : b.Align;
            tmp.enableWordWrapping = false;   // 줄은 표에서 직접 끊는다 — 자동 줄바꿈은 사진 위로 넘치게 만든다
            tmp.overflowMode = TextOverflowModes.Overflow;
            if (b.LineSpacing > 0f) tmp.lineSpacing = (b.LineSpacing - 1f) * 100f;

            float w = area.size.x * b.W;
            var rt = tmp.rectTransform;
            rt.sizeDelta = new Vector2(w, area.size.y * 0.5f);
            rt.pivot = new Vector2(0f, 1f);        // 좌상단 기준 — 표의 X·Y 가 글상자 좌상단이다

            // 정규화 좌표 → 부모(판넬) 로컬.
            //
            // 이 판넬은 **Y축 180° 로 놓여 있다** — 로컬 +z 가 월드 −z, 로컬 +x 가 월드 −x 다.
            // 그래서 두 가지를 보정한다.
            //   · 글상자를 180° 돌린다. 안 돌리면 글자가 판넬 뒤쪽을 향해 거울상으로 읽힌다.
            //   · 가로 좌표를 뒤집는다(왼쪽 칸이 왼쪽에 오도록). 안 뒤집으면 소개/연혁이 좌우로 바뀐다.
            // Y 는 표가 위에서 아래로 증가하므로 뒤집는다.
            float x = area.max.x - area.size.x * b.X;
            float y = area.max.y - area.size.y * b.Y;
            rt.position = new Vector3(x, y, faceZ + Lift);
            // TMP 글자는 transform 의 +z 가 보는 사람 반대쪽을 향할 때 바로 읽힌다 — 실측으로 확인했다.
            rt.rotation = Quaternion.Euler(0f, 180f, 0f);
            rt.localScale = Vector3.one;

            var r = go.GetComponent<MeshRenderer>();
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
        }

        /// <summary>
        /// 글자가 놓일 사각형을 <b>판넬 로컬 좌표</b>로 낸다. 기존 글자 메시(<c>(combined)</c>)의 로컬 범위를
        /// 그대로 쓴다 — 사진 배치와 이미 맞아 있어 기준으로 삼기 좋다.
        /// </summary>
        static Bounds TextArea(GameObject panel, out float faceZ)
        {
            var combined = panel.transform.Find(PanelName + " (combined)");
            var r = combined != null ? combined.GetComponent<Renderer>() : panel.GetComponent<Renderer>();
            var wb = r.bounds;                 // 월드 공간
            faceZ = wb.max.z;                  // 방 쪽이 +z 다
            var size = new Vector3(wb.size.x * 1.10f, wb.size.y * 1.22f, 0f);
            return new Bounds(new Vector3(wb.center.x, wb.center.y, wb.max.z), size);
        }

        static TMP_FontAsset LoadFont()
        {
            var f = Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (f == null) Debug.LogWarning("[PanelText] NotoSansKRBold_SDF 를 못 찾았다 — 기본 폰트로 그린다(한글이 깨질 수 있다).");
            return f;
        }

        static GameObject FindPanel()
        {
            foreach (var g in Object.FindObjectsByType<GameObject>(FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (g.name == PanelName) return g;
            return null;
        }

        static void EditorSceneManagerSave()
        {
            UnityEditor.SceneManagement.EditorSceneManager.MarkAllScenesDirty();
            UnityEditor.SceneManagement.EditorSceneManager.SaveOpenScenes();
        }
    }
}
