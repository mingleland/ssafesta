using System.Linq;
using TMPro;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 내부 부스 공간 12실 생성기 v3 (S15P21A604-445). 외부 FestivalSlot_XX 와 번호(boothId 1~12)로 1:1 연결.
    ///
    /// <para><b>규격의 단일 출처는 FE·BE 계약이다</b> — 부스는 6 × 6 × 2.72 m(ExpoKit 셸 실측), 레이아웃 좌표는 부스 로컬 미터,
    /// 정면(방문객이 들어오는 쪽)은 +z (FE passage.ts: "정면만 개방, 좌·우·후면은 벽"). 앵커 스케일 = 월드 규약 1 m = 13.26 unit.
    /// 셸은 로컬 스케일 1 로 두어 6.45 × 6.17 m 발판이 그대로 6 m 격자에 맞는다.</para>
    ///
    /// <para>v2 는 방 16 m·천장 9 m·셸 2.4배였다 — 6 m 레이아웃이 큰 홀 가운데 오도카니 놓이고, 출구 포털이 셸 뒤 벽에 있어
    /// 보이지도 않았다(사용자 지적 2026-09-06). v3 는 전시회 부스처럼: 셸 둘레 1.8 m 통로, 정면 3 m 통로, 천장 3.4 m,
    /// 정면 벽에 **문틀 + 발광 EXIT 사인 + 바닥 매트**, 스폰은 문 안쪽에서 부스를 바라본다. 조명은 부스 위 4 등 + 통로 1 등.</para>
    ///
    /// <para>NetworkObject 없음 (Booth 정적 오브젝트 Local Spawn 원칙). 외부 포털(Festival.prefab 안)은 건드리지 않는다 —
    /// 목적지는 이름 규약(Interior_NN/SpawnPoint, ReturnPoint_NN)으로 런타임에 해결된다(-330).</para>
    /// </summary>
    public static class FestaInteriorBuilder
    {
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string ShellPrefabPath = "Assets/_Project/Prefabs/Booth/BoothShell.prefab";
        const string RegistryPath = "Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset";

        const float M = 13.26f;                 // 1 m
        const float AnchorScale = M;            // 부스 로컬 1 m = 월드 13.26 unit (레이아웃 좌표 = 미터)
        // ── 방 규격 ─────────────────────────────────────────────────
        // 방 크기는 그대로 두고 **셸을 방에 맞춘다**(사용자 지시 2026-09-10 — 방을 부스에 맞추지 말 것).
        // 셸을 키우는 값은 아래 ShellFront* 상수에 있다.
        const float RoomHalfX = 5.0f * M;       // 방 폭 10 m
        const float RoomBackZ = -3.8f * M;      // 방 뒷벽
        const float RoomFrontZ = 7.0f * M;      // 앞쪽 통로 — 추적 카메라 기본 1.7 m·최대 3.4 m 가 벽에 눌리지 않게
        const float WallH = 6.0f * M;           // 천장 6.0 m — 3.4 m 는 갑갑하다는 지적으로 두 번 올렸다(3.4 → 4.4 → 6.0)

        // ── 셸을 방에 맞추기 (2026-09-10) ─────────────────────────────
        // 6 m 셸이 10 m 방 가운데 오도카니 놓여 "부스 벽이 방 크기에 비해 너무 작다" 는 지적을 받았다.
        //
        // **앵커 스케일은 건드리지 않는다.** 레이아웃 좌표는 부스 로컬 미터라는 FE·BE 계약이라, 앵커를 키우면
        // 노트북·책상까지 같이 커진다. 대신 **셸(벽·바닥)만** 늘려 방 벽까지 닿게 한다 — 오브젝트는 제 크기 그대로
        // 방 안쪽에 남는다. 스튜디오(6 m 격자)에서 배치할 수 있는 범위는 그대로이므로 계약도 그대로다.
        //
        // 셸 실측(스케일 1, 앵커 로컬 미터): x −3.092~+3.017(6.109 m), z ±3.017(6.034 m), y 0~2.715 m.
        const float ShellMinXMeters = -3.092f, ShellMaxXMeters = 3.017f;
        const float ShellMinZMeters = -3.017f, ShellMaxZMeters = 3.017f;
        const float ShellHeightMeters = 2.715f;
        // 정면 벽 안쪽 면(7.0 m)까지 **딱 붙인다** — 6.6 에서 끊자 셸 앞선과 벽 사이 0.4 m 가 흰 벽·회색 바닥 띠로 남아
        // "밑에 살짝 어긋난다"(사용자 2026-09-10 저녁). 5.6 → 6.6 → 7.0. 파샤는 벽 안쪽으로 옮긴다(아래).
        const float ShellTargetFrontZ = 7.0f;
        const float ShellTargetHeight = 5.6f;   // 천장(6.0 m) 아래 0.4 m — 위쪽 흰 띠를 최소로
        const float WallT = 0.3f * M;
        const float Pitch = 700f;               // 방 간격 70 m (v2 와 같음 — 외부 포털 목적지 이름만 쓰므로 위치는 자유)

        [MenuItem("Festa/World/내부 부스 공간 재생성 (v3)")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Interior] Play 중에는 저장 안 됨 — 종료 후 실행"); return; }

            var fest = GameObject.Find("/@Festival");
            var slots = fest != null ? fest.transform.Find("Festival_Slots") : null;
            if (slots == null) { Debug.LogError("[Interior] Festival_Slots 없음"); return; }

            var shellPrefab = AssetDatabase.LoadAssetAtPath<GameObject>(ShellPrefabPath);
            var registry = AssetDatabase.LoadAssetAtPath<ScriptableObject>(RegistryPath);
            if (shellPrefab == null || registry == null)
            { Debug.LogError($"[Interior] 셸({shellPrefab != null}) 또는 레지스트리({registry != null}) 없음"); return; }

            var oldRoot = GameObject.Find("/@BoothInteriors");
            if (oldRoot != null) Object.DestroyImmediate(oldRoot);
            var root = new GameObject("@BoothInteriors");

            var wallMat = Mat("InteriorWall", new Color(0.93f, 0.92f, 0.89f), 0.05f);
            // 통로 카펫 — 밝은 천장·흰 파샤와 어울리는 따뜻한 밝은 회색. 남색 셸이 도드라진다.
            var floorMat = Mat("InteriorFloor", new Color(0.66f, 0.64f, 0.61f), 0.08f);
            var trimMat = Mat("InteriorTrim", new Color(0.22f, 0.23f, 0.27f), 0.3f);
            // 출구 문 패널 — 파샤 스트라이프와 같은 계열의 짙은 남색. 흰 벽에서 문으로 읽히되 튀지 않는다.
            var doorMat = Mat("InteriorDoor", new Color(0.13f, 0.17f, 0.30f), 0.35f);
            var font = Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font == null) { Debug.LogError("[Interior] Resources/Fonts/NotoSansKRBold_SDF 없음"); return; }

            int i = 0;
            foreach (Transform slot in slots)
            {
                i++;
                int col = (i - 1) % 4, row = (i - 1) / 4;
                var center = new Vector3(700f + col * Pitch, 0f, row * Pitch);
                BuildRoom(root.transform, center, i, wallMat, floorMat, trimMat, doorMat, font, shellPrefab, registry);
            }

            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(root.scene);
            Debug.Log($"[Interior] v4 — 방 {i}실 (폭 {RoomHalfX * 2f / M:F1} m × 깊이 {(RoomFrontZ - RoomBackZ) / M:F1} m, 천장 {WallH / M:F1} m, 셸 6 m 스케일 1, 앵커 {AnchorScale:F2}) + 내부 포털 {i}개 — 씬 저장 필요");
        }

        static void BuildRoom(Transform parent, Vector3 c, int id, Material wall, Material floor, Material trim, Material doorMat,
                              TMP_FontAsset font, GameObject shellPrefab, ScriptableObject registry)
        {
            var room = new GameObject($"Interior_{id:D2}");
            room.transform.SetParent(parent, false);
            room.transform.position = c;

            GameObject Box(string name, Vector3 pos, Vector3 size, Material m, bool castShadow, bool collider = true)
            {
                var b = GameObject.CreatePrimitive(PrimitiveType.Cube);
                b.name = name;
                b.transform.SetParent(room.transform, false);
                b.transform.localPosition = pos;
                b.transform.localScale = size;
                if (!collider) Object.DestroyImmediate(b.GetComponent<Collider>());
                var r = b.GetComponent<Renderer>();
                r.sharedMaterial = m;
                r.shadowCastingMode = castShadow
                    ? UnityEngine.Rendering.ShadowCastingMode.On
                    : UnityEngine.Rendering.ShadowCastingMode.Off;
                GameObjectUtility.SetStaticEditorFlags(b, StaticEditorFlags.OccludeeStatic);   // 배칭 금지 (T-191)
                return b;
            }

            float zc = (RoomBackZ + RoomFrontZ) * 0.5f;          // 방 중심 z (부스 원점 기준 +1.2 m)
            float depth = RoomFrontZ - RoomBackZ;                // 10 m
            float width = RoomHalfX * 2f;                        // 10 m

            // 천장은 밝은 회백색 — 검은 천장(2차)은 노출 트러스와 함께 공사장처럼 읽혔다. 컨벤션홀의 흰 매입등 천장으로.
            var ceilMat = Mat("InteriorCeiling", new Color(0.82f, 0.82f, 0.84f), 0.10f);
            Box("Floor", new Vector3(0, -WallT / 2f, zc), new Vector3(width, WallT, depth), floor, false);
            Box("Ceiling", new Vector3(0, WallH + WallT / 2f, zc), new Vector3(width, WallT, depth), ceilMat, false);
            Box("Wall_Back", new Vector3(0, WallH / 2f, RoomBackZ - WallT / 2f), new Vector3(width, WallH, WallT), wall, true);
            Box("Wall_E", new Vector3(RoomHalfX + WallT / 2f, WallH / 2f, zc), new Vector3(WallT, WallH, depth), wall, true);
            Box("Wall_W", new Vector3(-RoomHalfX - WallT / 2f, WallH / 2f, zc), new Vector3(WallT, WallH, depth), wall, true);

            // 정면 벽은 **막힌 한 장**이다 (2026-09-10 사용자 지시 — "뚫린 문·검은 판·EXIT 글자가 이상하다. 막아 두고
            // 거기 박으면 상호작용 키가 뜨게"). 전에는 문 자리를 비우고 검은 배경판 + 문틀 + 발광 EXIT 텍스트를 붙였는데,
            // 텍스트 뒤 흰 사각·검은 구멍이 조악하게 보였다. 출구는 텔레포트 포털이라 실제로 열리는 문이 아니다 —
            // 벽에 문 모양 패널 하나를 살짝 도드라지게 붙이고, 포털의 거리·외곽선 기준을 그 패널로 둔다.
            Box("Wall_Front", new Vector3(0, WallH / 2f, RoomFrontZ + WallT / 2f), new Vector3(width, WallH, WallT), wall, true);

            // 출구 문 패널 — 벽면에서 0.06 m 도드라진 짙은 남색 **양문**(폭 2.8 m × 높이 3.2 m). 6 m 천장 벽에 2.4 m 문은
            // 짧아 보였다("프레임도 짧고", 사용자 2026-09-10) — 전시홀 출입구 비례로 키운다. 가운데 세로 홈 하나로 두 짝임을,
            // 양쪽 손잡이 바로 문임을 알린다. 콜라이더 있음: 여기에 "박히는" 것이 상호작용 진입이다.
            // 프롬프트·외곽선은 PortalInteractor 가 이 패널에 건다.
            float doorW = 2.8f * M, doorH = 3.2f * M;
            var door = Box("ExitDoor", new Vector3(0, doorH / 2f, RoomFrontZ - 0.03f * M), new Vector3(doorW, doorH, 0.06f * M), doorMat, true);
            Box("ExitDoorSplit", new Vector3(0, doorH / 2f, RoomFrontZ - 0.07f * M), new Vector3(0.03f * M, doorH, 0.02f * M), trim, false, false);
            Box("ExitDoorHandle_L", new Vector3(-0.22f * M, 1.05f * M, RoomFrontZ - 0.10f * M), new Vector3(0.05f * M, 0.8f * M, 0.05f * M), trim, false, false);
            Box("ExitDoorHandle_R", new Vector3(0.22f * M, 1.05f * M, RoomFrontZ - 0.10f * M), new Vector3(0.05f * M, 0.8f * M, 0.05f * M), trim, false, false);
            // 문 위 작은 안내 글자 — 남색 문 위에 흰 글자. EXIT 발광 사인·초록 판은 뺐다.
            WorldText(room.transform, "ExitDoorLabel", "축제로 나가기", new Vector3(0, doorH + 0.35f * M, RoomFrontZ - 0.02f * M),
                      Quaternion.identity, 0.28f * M, doorW, new Color(0.22f, 0.24f, 0.30f), font);

            // 부스 이름은 파샤(헤더)로 옮겼다 — 셸이 방 벽까지 닿으면서 뒷벽 사인이 셸 뒤에 가려 보이지 않는다.

            // ── 전시관 조명·구조 (2026-09-10 3차) ──────────────────────
            //
            // 1차: 흰 천장 + 둥근 전구 → "흰 상자에 전구". 2차: 검은 천장 + 노출 트러스 + LED 바 + 월 워시 스포트 →
            // "난잡하고 공사현장 같다"(사용자). 검은 빔·하드 스포트 콘·검은 걸레받이가 전시 오브젝트의 금속 트러스와
            // 겹쳐 공사장으로 읽혔다. 3차는 **컨벤션 센터의 정돈된 문법**으로 간다:
            //   · 밝은 회백색 천장에 **매입형 조명 패널**(부드러운 흰 사각, 격자 배치) — 코엑스·킨텍스 전시홀 천장.
            //   · 하드 스포트는 쓰지 않는다. 패널마다 낮은 세기의 포인트를 두어 **고르게** 밝힌다. 콘·핫스팟이 없어야 정돈된다.
            //   · 파샤(헤더)는 **흰 띠 + 브랜드 남색 스트라이프 + 남색 글자** — 전시 부스 간판의 전형.
            //   · 걸레받이는 짙은 회색 한 줄만. 트러스·검은 빔은 전부 뺀다.
            var panelMat = EmissiveMat("InteriorLightPanel", new Color(1f, 0.98f, 0.95f), 1.5f);   // 날아가지 않는 부드러운 흰빛
            var fasciaMat = Mat("InteriorFascia", new Color(0.96f, 0.96f, 0.95f), 0.12f);
            var accentMat = Mat("InteriorAccent", new Color(0.10f, 0.18f, 0.45f), 0.25f);        // 셸 패널과 같은 계열 남색

            // ① 매입 조명 패널 — 2열 × 3행 격자. 천장 면에 살짝 파묻혀 "매입등" 으로 읽힌다.
            int li = 0;
            for (int row = 0; row < 3; row++)
            for (int col = 0; col < 2; col++)
            {
                float px = (col == 0 ? -2.3f : 2.3f) * M;
                float pz = (-2.2f + row * 3.4f) * M;
                var panel = GameObject.CreatePrimitive(PrimitiveType.Cube);
                Object.DestroyImmediate(panel.GetComponent<Collider>());
                panel.name = $"CeilingLamp_{li}";
                panel.transform.SetParent(room.transform, false);
                panel.transform.localPosition = new Vector3(px, WallH - 0.03f * M, pz);
                panel.transform.localScale = new Vector3(1.6f * M, 0.06f * M, 1.2f * M);
                var pr = panel.GetComponent<Renderer>();
                pr.sharedMaterial = panelMat;
                pr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                GameObjectUtility.SetStaticEditorFlags(panel, StaticEditorFlags.OccludeeStatic);

                // 패널 아래 부드러운 채움광. 세기를 낮추고 개수로 채워 콘이 안 보이게 한다.
                var lgo = new GameObject($"BoothLight_{li}");
                lgo.transform.SetParent(room.transform, false);
                lgo.transform.localPosition = new Vector3(px, WallH - 0.35f * M, pz);
                var l = lgo.AddComponent<Light>();
                l.type = LightType.Point;
                l.color = new Color(1f, 0.97f, 0.93f);
                l.intensity = 170f;
                l.range = 9.5f * M;
                l.shadows = LightShadows.None;
                li++;
            }

            // ② 부스 파샤(헤더) — 부스 앞선 위를 가로지르는 흰 띠, 아래에 남색 스트라이프, 글자는 남색.
            // 글자는 +z(문 쪽)에서 -z 를 보며 읽으므로 180° — identity 로 두면 좌우가 뒤집힌다(2차 캡처에서 확인).
            // 셸이 정면 벽에 붙으면서 파샤는 **벽 안쪽 면**(문 위쪽)에 건다 — 방 안(-z 쪽)에서 +z 를 보며 읽으니 회전은 identity.
            // (셸 앞선 바깥에 두던 3차에서는 180° 였다 — 읽는 방향이 반대다.)
            float fasciaY = 4.7f * M, fasciaZ = RoomFrontZ - 0.13f * M;
            Box("BoothFascia", new Vector3(0, fasciaY, fasciaZ), new Vector3(width, 0.8f * M, 0.26f * M), fasciaMat, true, false);
            Box("BoothFasciaStripe", new Vector3(0, fasciaY - 0.46f * M, fasciaZ), new Vector3(width, 0.12f * M, 0.30f * M), accentMat, false, false);
            // 글자 높이 0.45 m — 파샤 띠(0.8 m) 안에서 스트라이프(아래 0.12 m)를 피해 가운데 놓인다.
            WorldText(room.transform, "FasciaText", $"BOOTH {id:D2}", new Vector3(0, fasciaY + 0.04f * M, fasciaZ - 0.15f * M),
                      Quaternion.identity, 0.45f * M, width - 1f * M, new Color(0.10f, 0.18f, 0.45f), font);

            // ③ 걸레받이 — 벽과 바닥이 곧바로 만나면 종이 상자처럼 보인다. 짙은 회색 한 줄로 바닥선만 잡는다.
            var skirtMat = Mat("InteriorSkirting", new Color(0.30f, 0.30f, 0.32f), 0.2f);
            Box("Skirting_E", new Vector3(RoomHalfX - 0.02f * M, 0.07f * M, zc), new Vector3(0.05f * M, 0.14f * M, depth), skirtMat, false, false);
            Box("Skirting_W", new Vector3(-RoomHalfX + 0.02f * M, 0.07f * M, zc), new Vector3(0.05f * M, 0.14f * M, depth), skirtMat, false, false);

            // ── 부스 앵커: FE 레이아웃 미터 좌표의 원점 (1 m = 13.26 unit) ──
            var anchor = new GameObject($"BoothSlot_{id}");
            anchor.transform.SetParent(room.transform, false);
            anchor.transform.localPosition = Vector3.zero;
            anchor.transform.localScale = Vector3.one * AnchorScale;

            // ExpoKit 셸 = FE 계약과 같은 6 × 6 × 2.72 m. 트러스(철근)는 제외.
            var shell = (GameObject)PrefabUtility.InstantiatePrefab(shellPrefab);
            shell.name = "BoothShell";   // BoothRuntime 의 셸 자동 탐색 이름
            shell.transform.SetParent(anchor.transform, false);
            shell.transform.localPosition = Vector3.zero;
            // 셸 원본 방향 그대로: 벽이 -x(좌)·-z(후면), 정면 +z 개방 = FE 계약(passage.ts). 실측 2026-09-06 — 180° 돌리면 벽이 +x·+z 로 간다.
            shell.transform.localRotation = Quaternion.identity;

            // 셸을 방에 맞춘다 — 좌우·뒤는 방 벽까지, 앞선은 통로를 먹지 않게 그대로, 높이는 천장 아래까지.
            // 앵커 로컬(미터) 기준으로 계산한다. 앵커 스케일(13.26)은 그대로이므로 오브젝트 크기는 변하지 않는다.
            float roomHalfXm = RoomHalfX / M, roomBackZm = RoomBackZ / M;
            float sx = (roomHalfXm * 2f) / (ShellMaxXMeters - ShellMinXMeters);
            float sz = (ShellTargetFrontZ - roomBackZm) / (ShellMaxZMeters - ShellMinZMeters);
            float sy = ShellTargetHeight / ShellHeightMeters;
            shell.transform.localScale = new Vector3(sx, sy, sz);
            // 스케일은 원점(앵커) 기준이라 중심이 어긋난다 — 목표 구간의 중심으로 되돌린다.
            shell.transform.localPosition = new Vector3(
                0f - (ShellMinXMeters + ShellMaxXMeters) * 0.5f * sx,
                0f,
                (roomBackZm + ShellTargetFrontZ) * 0.5f - (ShellMinZMeters + ShellMaxZMeters) * 0.5f * sz);
            foreach (var t in shell.GetComponentsInChildren<Transform>(true).ToArray())
                if (t != null && t.name.StartsWith("Truss"))
                    Object.DestroyImmediate(t.gameObject);
            // ── 오른쪽 벽 보강 (2026-09-10) ──────────────────────────
            // 벤더 셸은 **좌(-x)·후면(-z)** 만 세운다. 6 m 셸일 때는 눈에 덜 띄었지만 방 크기로 키우고 나니
            // 오른쪽이 흰 방 벽 그대로 남아 "빈 벽" 으로 보였다. 왼쪽 패널을 복제해 +x 로 대칭 배치한다.
            var leftPanels = shell.transform.Cast<Transform>()
                .Where(t => t.name.StartsWith("Panel02bCloth") && t.localPosition.x < -2.5f).ToArray();
            foreach (var p in leftPanels)
            {
                var copy = Object.Instantiate(p.gameObject, shell.transform);
                copy.name = p.name;
                copy.transform.localPosition = new Vector3(-p.localPosition.x, p.localPosition.y, p.localPosition.z);
                copy.transform.localRotation = Quaternion.Euler(270f, 270f, 0f);   // 좌측(y=90)의 거울 — 그래픽이 안쪽을 본다
                copy.transform.localScale = p.localScale;
            }

            // ── 셸 벽 콜라이더 (2026-09-10) ──────────────────────────
            // 벤더 프리팹에는 콜라이더가 **하나도 없다** — 부스 벽을 그대로 통과했다(사용자 지적).
            // 패널마다 상자 콜라이더를 붙인다. 천은 두께가 거의 0 이라 그대로 쓰면 빠른 이동에서 뚫리므로 최소 두께를 준다.
            foreach (var t in shell.GetComponentsInChildren<Transform>(true))
            {
                if (!t.name.StartsWith("Panel02bCloth")) continue;
                if (t.GetComponent<Collider>() != null) continue;
                var mf = t.GetComponent<MeshFilter>();
                if (mf == null || mf.sharedMesh == null) continue;
                var mb = mf.sharedMesh.bounds;
                var size = mb.size;
                size.x = Mathf.Max(size.x, 0.08f); size.y = Mathf.Max(size.y, 0.08f); size.z = Mathf.Max(size.z, 0.08f);
                var bc = t.gameObject.AddComponent<BoxCollider>();
                bc.center = mb.center;
                bc.size = size;
            }

            // 셸 카펫(Floor01)은 벤더 기본이 짙은 남색이라 방문객 시점 휘도가 0.2 대로 떨어진다(실측 2026-09-06).
            // 인스턴스 재질만 밝은 회청색 카펫으로 바꾼다 — 벤더 프리팹은 그대로.
            var carpet = Mat("InteriorBoothCarpet", new Color(0.52f, 0.55f, 0.64f), 0.05f);
            foreach (var r in shell.GetComponentsInChildren<Renderer>(true))
                if (r.gameObject.name.StartsWith("Floor"))
                {
                    var mats = r.sharedMaterials;
                    for (int mi = 0; mi < mats.Length; mi++) mats[mi] = carpet;
                    r.sharedMaterials = mats;
                }

            var runtimeType = System.AppDomain.CurrentDomain.GetAssemblies()
                .SelectMany(a => { try { return a.GetTypes(); } catch { return new System.Type[0]; } })
                .First(t => t.FullName == "Festa.Booth.BoothRuntime");
            var runtime = anchor.AddComponent(runtimeType);
            var so = new SerializedObject(runtime);
            so.FindProperty("_boothId").intValue = id;
            so.FindProperty("_registry").objectReferenceValue = registry;
            so.FindProperty("_loadOnStart").boolValue = false;   // WorldBoothPublishedBootstrap 이 게시본을 불러온다
            so.ApplyModifiedPropertiesWithoutUndo();

            // 스폰: 문 안쪽 통로, 부스를 바라본다(-z)
            var spawn = new GameObject("SpawnPoint");
            spawn.transform.SetParent(room.transform, false);
            // 셸 앞선에서 0.75 m 앞. 문 매트(5.1~6.3 m)까지 1.2 m 남아 입장 직후엔 "나가기" 프롬프트가 뜨지 않고, 한 걸음 물러서면 뜬다.
            spawn.transform.localPosition = new Vector3(0, 0.1f * M, 3.9f * M);
            spawn.transform.localRotation = Quaternion.Euler(0f, 180f, 0f);

            // 출구 포털 — 문 앞. 거리·하이라이트는 문틀 기준, 프롬프트는 "축제로 나가기". 목적지는 이름 규약(ReturnPoint_NN)으로 런타임 해결.
            var portalGo = new GameObject($"Portal_Int_{id:D2}");
            portalGo.transform.SetParent(room.transform, false);
            portalGo.transform.localPosition = new Vector3(0, 0, RoomFrontZ - 0.4f * M);
            var portal = portalGo.AddComponent<Festa.World.BoothPortal>();
            portal.boothId = id;
            var ret = GameObject.Find($"ReturnPoint_{id:D2}");
            portal.destination = ret != null ? ret.transform : null;
            portal.promptText = "축제로 나가기";
            // 2026-09-10 사거리 축소 — 매트 표면에서 12u(0.9 m). 바깥 부스 입장과 같은 값이라야 조작감이 갈리지 않는다.
            portal.interactRadius = 12f;
            portal.requireFacing = false;
            portal.outlineWidth = 0.5f;
            // 거리·외곽선 기준은 **문 패널**이다. 벽에 몸이 닿으면(캡슐 반경 2.75u) 표면 거리 ≈ 3u 라 12u 안에 들고,
            // 문 패널 자체가 금색 외곽선으로 켜져 "여기에 F" 가 읽힌다. 바닥 매트·EXIT 텍스트는 뺐다.
            portal.boundsSource = door.GetComponent<Renderer>();
            portal.highlightRoot = door.transform;
        }

        /// <summary>
        /// 월드 3D 글자 — **TextMeshPro(SDF)**. 처음(v3)에는 레거시 <see cref="TextMesh"/> + LegacyRuntime 비트맵 폰트였는데
        /// 글자가 흐릿하고 가장자리가 뭉개져 "글씨가 이상하다"(사용자 2026-09-10). 이름표·이정표와 같은 SDF 경로로 바꾼다 —
        /// WebGL 에서 검증된 렌더링이고 어느 거리에서나 선이 살아 있다. fontSize 10 = 월드 1 unit (WorldNameplate 실측).
        /// </summary>
        static void WorldText(Transform parent, string name, string text, Vector3 localPos, Quaternion rot, float heightUnits, float widthUnits, Color color, TMP_FontAsset font)
        {
            // RectTransform 을 먼저 붙인다 — 뒤에 붙이면 Transform 이 교체되어 참조가 깨진다(S15P21A604-355).
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = rot;
            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = font;
            tmp.text = text;
            tmp.color = color;
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.fontSize = heightUnits * 10f;
            tmp.characterSpacing = 6f;   // 간판 글자는 자간을 살짝 벌려야 읽힌다
            tmp.rectTransform.sizeDelta = new Vector2(widthUnits, heightUnits * 1.4f);
            var tr = go.GetComponent<MeshRenderer>();
            tr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            tr.receiveShadows = false;
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

        static Material EmissiveMat(string name, Color c, float intensity)
        {
            var m = Mat(name, c, 0.2f);
            m.EnableKeyword("_EMISSION");
            m.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            m.SetColor("_EmissionColor", c * intensity);
            EditorUtility.SetDirty(m);
            return m;
        }
    }
}
