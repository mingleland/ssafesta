using TMPro;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 길찾기 표지판 생성기 (S15P21A604-452). 스폰(로비) → 복도 → 축제 광장(티켓 부스·부스 거리·게임기) 동선의 갈림길 4곳에
    /// 나무 기둥 + 화살 판 표지판을 세운다. 판은 **실제 목적지 좌표를 향해** 돌아간다 — 배치를 옮겨도 메뉴 한 번으로 다시 맞는다.
    ///
    /// <para>왜 — 축제 광장은 스폰에서 약 68 m 떨어져 있고 복도가 한 번 꺾인다. 월드 어디에도 방향 표시가 없어 처음 온 사람은
    /// 헤맨다(2026-09-06 재점검). 일반적인 게임의 갈림길 표지판 문법을 따른다.</para>
    ///
    /// <para>글자는 TextMeshPro 3D(<c>Fonts/Jua_SDF</c>) — 이름표와 같은 경로라 WebGL 에서 검증된 렌더링이다. 판 양면에 같은 글을 둔다.
    /// 씬 루트 <c>@Wayfinding</c> 아래에만 만들고 기존 오브젝트는 건드리지 않는다. NetworkObject 없음.</para>
    /// </summary>
    public static class FestaWayfindingBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const float M = 13.26f;   // 1 m
        const string RootName = "@Wayfinding";

        // 동선의 기준점 (월드 unit). 표지판 판은 이 좌표를 향한다.
        static readonly Vector3 Spawn         = new(-29.5f, 0f, -248f);
        static readonly Vector3 CorridorTurn  = new(-93f, 0f, 160f);     // 복도 북단 — 여기서 서쪽으로 꺾인다
        static readonly Vector3 Forecourt     = new(-200f, 0f, 145f);    // 복도 → 축제 광장 입구
        static readonly Vector3 Ticket        = new(-259.5f, 0f, 145f);  // 티켓 부스 창구(부스 임대 NPC)
        static readonly Vector3 BoothStreet   = new(-580f, 0f, 145f);    // 부스 거리 가운데
        static readonly Vector3 Arcade        = new(-905f, 0f, 140f);    // 게임기·슬롯머신 줄

        struct Board { public string text; public Vector3 target; public Board(string t, Vector3 v) { text = t; target = v; } }

        [MenuItem("Festa/World/길찾기 표지판 재생성 (-452)")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Wayfinding] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var old = GameObject.Find("/" + RootName);
            if (old != null) Object.DestroyImmediate(old);
            var root = new GameObject(RootName);
            Undo.RegisterCreatedObjectUndo(root, "Wayfinding");

            var wood = Mat("SignWood", new Color(0.36f, 0.24f, 0.14f), 0.25f);
            var board = Mat("SignBoard", new Color(0.97f, 0.93f, 0.82f), 0.15f);
            var font = Resources.Load<TMP_FontAsset>("Fonts/Jua_SDF") ?? Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font == null) { Debug.LogError("[Wayfinding] Resources/Fonts/Jua_SDF 없음"); return; }

            // 1) 로비 — 복도 입구 오른쪽(동쪽) 옆. 스폰에서 북서쪽을 보면 보인다.
            Post(root.transform, "Sign_Lobby", new Vector3(-48f, 0f, -140f), Spawn, wood, board, font,
                new Board("축제 광장 · 부스 거리", CorridorTurn));

            // 2) 복도 북단(꺾이는 곳) — 동쪽 벽 옆. 북쪽으로 걸어오면 정면.
            Post(root.transform, "Sign_CorridorTurn", new Vector3(-62f, 0f, 178f), new Vector3(-93f, 0f, 0f), wood, board, font,
                new Board("축제 광장 · 티켓 부스", Forecourt),
                new Board("미니게임 · 게임기", Arcade));

            // 3) 축제 광장 입구(앞마당) — 티켓 부스 남동쪽. 갈림길: 창구 / 부스 거리 / 게임기 / 로비.
            Post(root.transform, "Sign_Forecourt", new Vector3(-228f, 0f, 108f), new Vector3(-150f, 0f, 145f), wood, board, font,
                new Board("티켓 부스 · 부스 임대", Ticket),
                new Board("부스 거리 (1~12)", BoothStreet),
                new Board("미니게임 · 게임기", Arcade),
                new Board("로비로 돌아가기", CorridorTurn));

            // 4) 부스 거리 서쪽 절반 — 양쪽 끝 안내 (벤더 WEST/EAST 표지판(-580,150)과 떨어뜨린다).
            Post(root.transform, "Sign_BoothStreet", new Vector3(-700f, 0f, 188f), new Vector3(-450f, 0f, 145f), wood, board, font,
                new Board("미니게임 · 게임기", Arcade),
                new Board("티켓 부스 · 로비", Ticket));

            EditorSceneManager_MarkDirty(root);
            Debug.Log("[Wayfinding] 표지판 4개 생성 — @Wayfinding");
        }

        static void EditorSceneManager_MarkDirty(GameObject go) =>
            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(go.scene);

        /// <summary>
        /// 기둥 하나 + 판 N 개. 판은 위에서부터 1.95 m 에서 0.32 m 간격으로 내려온다.
        /// <paramref name="approachFrom"/> 은 사람이 주로 걸어오는 지점 — 판이 그 시선과 거의 평행(화살이 곧장 멀어지는 방향)이면
        /// 옆면만 보여 글자가 안 읽히므로(로비 표지판 첫 캡처), 최소 35° 는 비스듬히 보이도록 화살을 돌린다. 30° 안팎 틀어진
        /// 화살도 방향은 충분히 전달된다 — 실제 갈림길 표지판이 그렇게 서 있다.
        /// </summary>
        static void Post(Transform parent, string name, Vector3 pos, Vector3 approachFrom, Material wood, Material boardMat, TMP_FontAsset font, params Board[] boards)
        {
            var post = new GameObject(name);
            post.transform.SetParent(parent, false);
            post.transform.position = GroundAt(pos);

            // 기둥: 반지름 0.07 m, 높이 2.3 m
            var pole = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            pole.name = "Pole";
            pole.transform.SetParent(post.transform, false);
            pole.transform.localScale = new Vector3(0.14f * M, 1.15f * M, 0.14f * M);
            pole.transform.localPosition = new Vector3(0f, 1.15f * M, 0f);
            pole.GetComponent<Renderer>().sharedMaterial = wood;
            Object.DestroyImmediate(pole.GetComponent<Collider>());   // 걷기 충돌은 기둥 하나로 충분 — 아래 캡슐
            var col = post.AddComponent<CapsuleCollider>();
            col.radius = 0.12f * M; col.height = 2.3f * M; col.center = new Vector3(0f, 1.15f * M, 0f);

            // 받침: 작은 원판
            var foot = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            foot.name = "Foot";
            foot.transform.SetParent(post.transform, false);
            foot.transform.localScale = new Vector3(0.36f * M, 0.03f * M, 0.36f * M);
            foot.transform.localPosition = new Vector3(0f, 0.03f * M, 0f);
            foot.GetComponent<Renderer>().sharedMaterial = wood;
            Object.DestroyImmediate(foot.GetComponent<Collider>());

            float y = 1.95f * M;
            foreach (var b in boards)
            {
                Arrow(post.transform, b, y, approachFrom, wood, boardMat, font);
                y -= 0.32f * M;
            }
        }

        const float MinReadAngle = 35f;

        /// <summary>화살 판 — 판(1.25 × 0.26 × 0.05 m) + 45° 돌린 끝(화살촉). 로컬 +x 가 목적지를 향한다. 글자는 양면.</summary>
        static void Arrow(Transform post, Board b, float y, Vector3 approachFrom, Material wood, Material boardMat, TMP_FontAsset font)
        {
            var dir = b.target - post.position; dir.y = 0f;
            if (dir.sqrMagnitude < 1e-3f) dir = Vector3.forward;
            dir.Normalize();

            // 주 접근 시선(approach → 기둥)과 화살이 거의 평행이면 판이 옆면만 보인다 → 최소 각을 확보하도록 화살을 돌린다.
            var view = post.position - approachFrom; view.y = 0f;
            if (view.sqrMagnitude > 1e-3f)
            {
                view.Normalize();
                float a = Vector3.SignedAngle(view, dir, Vector3.up);          // -180..180, 0 = 곧장 멀어짐, ±180 = 곧장 다가옴
                float off = Mathf.Abs(a) <= 90f ? Mathf.Abs(a) : 180f - Mathf.Abs(a);
                if (off < MinReadAngle)
                {
                    float sign = a >= 0f ? 1f : -1f;
                    if (Mathf.Abs(a) > 90f) sign = -sign;                    // 다가오는 쪽은 반대로 돌려야 각이 커진다
                    dir = Quaternion.AngleAxis(sign * (MinReadAngle - off), Vector3.up) * dir;
                }
            }
            float yaw = Mathf.Atan2(dir.x, dir.z) * Mathf.Rad2Deg;   // +z 기준 시계방향 각

            var arm = new GameObject("Arrow_" + b.text);
            arm.transform.SetParent(post, false);
            arm.transform.localPosition = new Vector3(0f, y, 0f);
            // 로컬 +x 를 dir 로: 기본 +x 는 yaw 90° 이므로 (yaw - 90)
            arm.transform.localRotation = Quaternion.Euler(0f, yaw - 90f, 0f);

            const float len = 1.25f, h = 0.26f, t = 0.05f;
            var plate = GameObject.CreatePrimitive(PrimitiveType.Cube);
            plate.name = "Plate";
            plate.transform.SetParent(arm.transform, false);
            plate.transform.localScale = new Vector3(len * M, h * M, t * M);
            plate.transform.localPosition = new Vector3((0.09f + len / 2f) * M, 0f, 0f);
            plate.GetComponent<Renderer>().sharedMaterial = boardMat;
            Object.DestroyImmediate(plate.GetComponent<Collider>());

            var tip = GameObject.CreatePrimitive(PrimitiveType.Cube);
            tip.name = "Tip";
            tip.transform.SetParent(arm.transform, false);
            float side = h * 0.7071f;   // 45° 회전한 정사각형의 대각선 = 판 높이
            tip.transform.localScale = new Vector3(side * M, side * M, t * 0.98f * M);
            tip.transform.localPosition = new Vector3((0.09f + len) * M, 0f, 0f);
            tip.transform.localRotation = Quaternion.Euler(0f, 0f, 45f);
            tip.GetComponent<Renderer>().sharedMaterial = boardMat;
            Object.DestroyImmediate(tip.GetComponent<Collider>());

            // 테두리 느낌의 얇은 나무 띠(판 뒤)
            var back = GameObject.CreatePrimitive(PrimitiveType.Cube);
            back.name = "Rim";
            back.transform.SetParent(arm.transform, false);
            back.transform.localScale = new Vector3((len + 0.04f) * M, (h + 0.04f) * M, t * 0.6f * M);
            back.transform.localPosition = new Vector3((0.09f + len / 2f) * M, 0f, 0f);
            back.GetComponent<Renderer>().sharedMaterial = wood;
            Object.DestroyImmediate(back.GetComponent<Collider>());

            // 글자 — 판 양면. TMP 3D 는 -z 쪽에서 읽힌다(카메라가 +z 를 볼 때). 반대면은 y 180°.
            float cx = (0.09f + len / 2f) * M;
            Text(arm.transform, b.text, new Vector3(cx, 0f, -(t / 2f + 0.004f) * M), Quaternion.identity, len, h, font);
            Text(arm.transform, b.text, new Vector3(cx, 0f, (t / 2f + 0.004f) * M), Quaternion.Euler(0f, 180f, 0f), len, h, font);
        }

        static void Text(Transform parent, string text, Vector3 localPos, Quaternion rot, float lenM, float hM, TMP_FontAsset font)
        {
            var go = new GameObject("Text");
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = rot;
            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = font;
            tmp.text = text;
            tmp.color = new Color(0.16f, 0.13f, 0.10f, 1f);
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.enableWordWrapping = false;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.enableAutoSizing = true;
            tmp.fontSizeMin = 6f; tmp.fontSizeMax = 27f;   // 글자 높이 ≈ 0.18 m (첫 캡처에서 22 는 작았다)
            tmp.rectTransform.sizeDelta = new Vector2((lenM - 0.10f) * M, hM * M);
            tmp.sortingOrder = 0;
            var r = go.GetComponent<Renderer>();
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            r.receiveShadows = false;
        }

        /// <summary>바닥 높이를 물리로 찾는다 — 상수로 박으면 바닥 메시가 바뀔 때 뜬다.</summary>
        static Vector3 GroundAt(Vector3 pos)
        {
            if (Physics.Raycast(pos + Vector3.up * 30f, Vector3.down, out var hit, 80f))
                return new Vector3(pos.x, hit.point.y, pos.z);
            Debug.LogWarning($"[Wayfinding] {pos:F0} 아래에 바닥이 없다 — y=0 사용");
            return new Vector3(pos.x, 0f, pos.z);
        }

        static Material Mat(string name, Color c, float smooth)
        {
            var m = AssetDatabase.LoadAssetAtPath<Material>(MatDir + name + ".mat");
            if (m == null)
            {
                m = new Material(Shader.Find("Universal Render Pipeline/Lit"));
                AssetDatabase.CreateAsset(m, MatDir + name + ".mat");
            }
            m.color = c;
            m.SetFloat("_Smoothness", smooth);
            EditorUtility.SetDirty(m);
            return m;
        }
    }
}
