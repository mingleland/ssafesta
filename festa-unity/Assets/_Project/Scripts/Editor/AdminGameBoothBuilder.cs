using Festa.Booth;
using Festa.Minigame;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 관리자 게임 부스를 씬에 세운다 (spec 014 FR-001, S15P21A604-293).
    ///
    /// ── 왜 부스 오브젝트 타입이 아닌가 ────────────────────────
    /// 부스 오브젝트 10종(`AI_AGENT`·`LAPTOP` …)은 Published Layout API 의 **JSON 계약**이라
    /// `GAME_KIOSK` 같은 타입을 Unity 혼자 늘릴 수 없다 (헌법 24조).
    /// 그리고 기획서상 게임 부스는 사용자가 만드는 부스가 아니다 —
    /// docs/01 §"관리자 상시 부스 3: 자소서 AI / 사주 AI / **관리자 게임**".
    /// 즉 **월드 고정 설치물**이므로 씬에 직접 세우는 게 맞다.
    ///
    /// ── 왜 런타임 생성이 아닌가 ───────────────────────────────
    /// 입장 게이트는 연출이라 런타임에 만들고 지웠지만, 이건 **월드에 계속 있는 집기**다.
    /// 씬에 있어야 배치를 눈으로 조정하고 콜라이더·조명과 함께 검토할 수 있다.
    /// 대신 손으로 만들지 않고 이 빌더로 재생성해 배치 근거를 코드에 남긴다
    /// (`FestaInteriorBuilder` 등 기존 월드 빌더와 같은 방식).
    ///
    /// ── 위치 근거 ─────────────────────────────────────────────
    /// 엘리베이터(x 15~41)에서 나온 플레이어는 서쪽을 본다. 그 시야의 왼쪽(남쪽) 카펫이
    /// 비어 있고(레이캐스트로 x −135~−15 구간 빈 바닥 확인), SSAFY 조형물·무대로 가는
    /// 主동선을 막지 않는다. 그래서 그 자리에 놓고 **스폰 지점을 바라보게** 돌린다.
    /// </summary>
    public static class AdminGameBoothBuilder
    {
        const string RootName = "@AdminGameBooth";

        // 1 m = 10 유닛. 사람 키가 약 16 유닛이라 그 기준으로 잡았다.
        static readonly Vector3 Spot = new(-80f, 0f, -290f);
        static readonly Vector3 LookAt = new(-29.5f, 0f, -248f);   // 스폰 지점

        [MenuItem("Festa/World/관리자 게임 부스 재생성")]
        public static void Rebuild()
        {
            if (Application.isPlaying)
            {
                Debug.LogWarning("[GameBooth] Play 중에는 씬이 저장되지 않는다 — 종료 후 실행해라.");
                return;
            }

            var old = GameObject.Find("/" + RootName);
            if (old != null) Object.DestroyImmediate(old);

            // 바닥 높이를 실측해서 얹는다. 상수로 박으면 바닥이 바뀔 때 공중에 뜬다.
            //
            // **가장 먼저 맞은 면을 쓰면 안 된다.** 이 월드에는 콜리전 밀봉용 @World11F_Shell 의
            // Shell_Roof 가 y=84 에 깔려 있어서, 위에서 쏘면 지붕에 얹힌다 (실측으로 당했다).
            // 아래로 쏜 것 중 **가장 낮은 지점**이 사람이 서는 바닥이다.
            float floorY = Spot.y;
            var hits = Physics.RaycastAll(new Vector3(Spot.x, 120f, Spot.z), Vector3.down, 200f);
            if (hits.Length > 0)
            {
                floorY = float.MaxValue;
                foreach (var h in hits) floorY = Mathf.Min(floorY, h.point.y);
            }
            else
            {
                Debug.LogWarning($"[GameBooth] ({Spot.x}, {Spot.z}) 아래에서 바닥을 못 찾았다 — y={Spot.y} 로 놓는다.");
                floorY = Spot.y;
            }

            var root = new GameObject(RootName);
            root.transform.position = new Vector3(Spot.x, floorY, Spot.z);
            var toSpawn = new Vector3(LookAt.x - Spot.x, 0f, LookAt.z - Spot.z);
            root.transform.rotation = Quaternion.LookRotation(toSpawn.normalized, Vector3.up);

            var cabinet = Cabinet(root.transform);
            Screen(root.transform);
            Sign(root.transform);

            // 클릭 하이라이트·거리 제한은 기존 부스 오브젝트와 같은 컴포넌트를 쓴다.
            var target = root.AddComponent<BoothInteractionTarget>();
            target.Configure(35f, true);   // 3.5 m 안에서 클릭 가능
            root.AddComponent<MinigameInteractable>();

            Undo.RegisterCreatedObjectUndo(root, "관리자 게임 부스 생성");
            Selection.activeGameObject = root;
            EditorSceneManager.MarkSceneDirty(root.scene);

            Debug.Log($"[GameBooth] 생성 완료 — pos={root.transform.position} " +
                      $"바닥y={floorY:F1} 회전={root.transform.eulerAngles.y:F0}° (스폰을 바라봄)\n" +
                      "씬 저장은 손으로 한다 (Ctrl+S) — 빌더가 임의로 저장하지 않는다.");
        }

        /// <summary>본체 — 어두운 캐비닛에 SSAFY 파랑 허리띠.</summary>
        static GameObject Cabinet(Transform parent)
        {
            var body = Box(parent, "Cabinet", new Vector3(0f, 6f, 0f), new Vector3(14f, 12f, 9f),
                           new Color(0.10f, 0.11f, 0.14f));
            Box(parent, "Plinth", new Vector3(0f, 0.6f, 0f), new Vector3(16f, 1.2f, 11f),
                new Color(0.06f, 0.065f, 0.08f));
            // 허리띠는 장식이라 콜라이더를 빼 클릭 판정이 두 겹이 되지 않게 한다.
            var belt = Box(parent, "Accent", new Vector3(0f, 8.4f, -4.7f), new Vector3(14.2f, 1.6f, 0.5f),
                           new Color(0.24f, 0.31f, 0.77f));
            Object.DestroyImmediate(belt.GetComponent<Collider>());
            return body;
        }

        /// <summary>기울어진 화면. 게이트 표시기와 같은 앰버 머티리얼을 재사용한다.</summary>
        static void Screen(Transform parent)
        {
            var screen = Box(parent, "Screen", new Vector3(0f, 13.4f, -2.2f), new Vector3(12.5f, 8.5f, 0.6f), Color.black);
            screen.transform.localRotation = Quaternion.Euler(-22f, 0f, 0f);
            Object.DestroyImmediate(screen.GetComponent<Collider>());

            // Resources 에 있는 에셋을 참조해야 빌드에 셰이더가 들어간다 (T-213).
            var amber = Resources.Load<Material>("GateSegment");
            if (amber != null) screen.GetComponent<MeshRenderer>().sharedMaterial = amber;
            else Debug.LogError("[GameBooth] Resources/GateSegment.mat 을 못 찾아 화면이 검게 남는다.");
        }

        /// <summary>
        /// 안내 간판. TextMesh 대신 월드 스페이스 캔버스를 쓴다 —
        /// 한글을 확실히 출력하려면 로비·미니게임 HUD 와 같은 uGUI + MalgunGothicLight 조합이 안전하다.
        /// </summary>
        static void Sign(Transform parent)
        {
            var canvasGo = new GameObject("Sign", typeof(RectTransform), typeof(Canvas));
            canvasGo.transform.SetParent(parent, false);
            canvasGo.transform.localPosition = new Vector3(0f, 19.6f, -1.4f);
            canvasGo.transform.localRotation = Quaternion.Euler(0f, 180f, 0f);   // 앞면이 플레이어를 향하도록

            var canvas = canvasGo.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.WorldSpace;
            var rect = canvasGo.GetComponent<RectTransform>();
            rect.sizeDelta = new Vector2(320f, 90f);
            rect.localScale = Vector3.one * 0.06f;   // 320 * 0.06 ≈ 19 유닛 폭

            // 글씨만 띄우면 허공에 뜬 것처럼 보인다 — 뒤에 판을 대야 간판으로 읽힌다.
            var plate = new GameObject("Plate", typeof(RectTransform), typeof(UnityEngine.UI.Image));
            plate.transform.SetParent(rect, false);
            var plateImage = plate.GetComponent<UnityEngine.UI.Image>();
            plateImage.color = new Color(0.09f, 0.10f, 0.13f, 0.97f);
            var plateRect = plate.GetComponent<RectTransform>();
            plateRect.anchorMin = Vector2.zero; plateRect.anchorMax = Vector2.one;
            plateRect.offsetMin = plateRect.offsetMax = Vector2.zero;

            var edge = new GameObject("PlateEdge", typeof(RectTransform), typeof(UnityEngine.UI.Image));
            edge.transform.SetParent(rect, false);
            edge.GetComponent<UnityEngine.UI.Image>().color = new Color(0.94f, 0.62f, 0.29f, 1f);
            var edgeRect = edge.GetComponent<RectTransform>();
            edgeRect.anchorMin = new Vector2(0f, 0f); edgeRect.anchorMax = new Vector2(1f, 0f);
            edgeRect.pivot = new Vector2(0.5f, 0f);
            edgeRect.offsetMin = new Vector2(0f, 0f); edgeRect.offsetMax = new Vector2(0f, 4f);

            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            if (font == null) Debug.LogError("[GameBooth] Resources/Fonts/MalgunGothicLight 를 못 찾아 간판 한글이 깨진다.");

            Text(rect, font, "타이밍 스톱", 44, FontStyle.Bold, new Color(1f, 0.82f, 0.42f), new Vector2(0f, 16f));
            Text(rect, font, "F 키로 시작", 26, FontStyle.Normal, new Color(0.86f, 0.86f, 0.9f), new Vector2(0f, -22f));
        }

        static void Text(RectTransform parent, Font font, string text, int size, FontStyle style, Color color, Vector2 pos)
        {
            var go = new GameObject("Label", typeof(RectTransform), typeof(UnityEngine.UI.Text));
            go.transform.SetParent(parent, false);
            var label = go.GetComponent<UnityEngine.UI.Text>();
            label.font = font != null ? font : Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            label.text = text;
            label.fontSize = size;
            label.fontStyle = style;
            label.color = color;
            label.alignment = TextAnchor.MiddleCenter;
            label.horizontalOverflow = HorizontalWrapMode.Overflow;
            label.verticalOverflow = VerticalWrapMode.Overflow;
            var rect = go.GetComponent<RectTransform>();
            rect.sizeDelta = new Vector2(320f, 50f);
            rect.anchoredPosition = pos;
        }

        static GameObject Box(Transform parent, string name, Vector3 localPos, Vector3 size, Color color)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localScale = size;

            // 런타임 머티리얼은 URP Lit 을 명시한다 (프로젝트 규칙). 기본 큐브 머티리얼은
            // Built-in 이라 URP 에서 분홍으로 뜬다.
            var shader = Shader.Find("Universal Render Pipeline/Lit");
            var material = new Material(shader) { color = color };
            material.SetFloat("_Smoothness", 0.25f);
            go.GetComponent<MeshRenderer>().sharedMaterial = material;
            return go;
        }
    }
}
