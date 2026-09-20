// 오락실(스튜디오 게임 진열 공간)을 씬에 짓는 에디터 도구.
// 이 파일이 있는 이유: 방·복도·캐비닛 20대를 손으로 놓으면 간격과 번호가 어긋나고, 다시 만들 수도 없다.
// 좌표 규칙을 코드에 두면 값 하나만 바꿔 다시 지을 수 있고 검증도 같은 값으로 돈다.
using System.Collections.Generic;
using TMPro;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제존 서쪽 벽을 열어 그 너머에 <b>오락실</b>을 짓는다 — 복도 9 m → 방 15 × 11 m, 캐비닛 20대.
    ///
    /// <para><b>왜 서쪽인가.</b> 축제장 벽 안쪽은 x −930~−220 인데 부스는 x −875~−300 에만 있다.
    /// 서쪽 끝 55 유닛 띠가 부스를 건드리지 않고 쓸 수 있는 유일한 면이고, 길찾기 표지판 네 개가
    /// 이미 그쪽(−905, 140)의 오락기 줄을 가리킨다. 남·북 벽은 부스 줄이 붙어 있어 못 뚫는다.</para>
    ///
    /// <para><b>왜 복도를 두는가.</b> 문 하나로 붙이면 세 가지가 한꺼번에 나빠진다 — BGM 이 문턱에서
    /// 0.3 초 만에 갈아타 툭 끊기고, 밝기가 급변해 눈이 못 따라가며, 축제존에서 방 안이 들여다보여
    /// 캐비닛 20대가 계속 그려진다. 9 m 짜리 복도 하나가 셋을 다 해결한다.</para>
    ///
    /// <para><b>자리마다 값이 다르다.</b> 들어와서 정면으로 보이는 섬 6대가 프라임이고 안쪽 구석이
    /// 가장 약하다. 번호도 그 순서라 <c>arcade-01</c> 이 제일 좋은 자리다 — 사용자가 선착순으로
    /// 고르는 구조(GitLab #256)라 번호와 가치가 어긋나면 고를 때 혼란스럽다.</para>
    ///
    /// <para>씬 루트 <c>@Arcade</c> 아래에만 만들고 기존 오브젝트는 건드리지 않는다. NetworkObject 없음 —
    /// 캐비닛은 월드 고정물이고 상호작용은 각자 로컬에서 판정한다(헌법 4조).</para>
    /// </summary>
    public static class FestaArcadeRoomBuilder
    {
        const float M = 13.26f;              // 1 m — 월드 공용 환산 (FestaWayfindingBuilder 와 같은 값)
        const string RootName = "@Arcade";
        const string CabinetPrefab = "Assets/_Project/Prefabs/World/ArcadeCabinet_Plaza.prefab";
        const string MatDir = "Assets/_Project/Art/World/Materials/";
        const string WestWallPath = "@Festival/Festival_Walls/Wall_West";

        // ── 좌표 ────────────────────────────────────────────────
        // 축제장 서쪽 벽. FestivalMinimapArea.MinX 와 같은 면이다.
        const float WestWallX = -930f;
        const float CorridorZ = 15f;         // 서쪽 벽 남쪽 빈 구간 — z 140의 하이스트라이커/기존 오락기 줄은 보존한다
        const float CorridorLen = 9f * M;
        const float CorridorWidth = 3.5f * M;
        const float CorridorHeight = 3.0f * M;   // 방보다 낮다 — 좁혔다 푸는 것이 들어설 때의 해방감을 만든다
        // 축제장 내부 구획 원안. InteriorScanBatch로 실제 간섭을 검사한 뒤 좌표를 확정한다.
        const float RoomDepth = 15f * M;
        const float RoomWidth = 11f * M;
        const float RoomHeight = 3.5f * M;
        const float Thickness = 0.3f * M;    // 벽 두께

        static float RoomEastX => WestWallX - CorridorLen;          // 방의 입구 쪽 벽
        static float RoomWestX => RoomEastX - RoomDepth;            // 방의 안쪽 끝
        static float RoomMinZ => CorridorZ - RoomWidth * 0.5f;
        static float RoomMaxZ => CorridorZ + RoomWidth * 0.5f;

        // ── 캐비닛 배치 ──────────────────────────────────────────
        // 간격 1.9 m — 붙여 세우면 자판기 벽이 되고, 더 벌리면 오락실이 아니라 전시장이 된다.
        const float CabinetPitch = 1.9f * M;
        const float EntranceClear = 2f * M;  // 입구 앞 여유 — 들어서자마자 기계에 부딪히지 않게
        const float WallInset = 0.5f * M;    // 벽에서 띄우는 거리
        const float IslandHalfGap = 0.65f * M; // 실측 캐비닛 깊이 0.92 m — 등을 맞대도 메시가 겹치지 않게// 섬 두 줄이 등을 맞대는 간격의 절반
        const int WallRowCount = 7;          // 북·남 각 줄
        const int IslandRowCount = 3;        // 섬 한 줄

        [MenuItem("Festa/World/오락실 생성 (복도 + 방 + 캐비닛 20대)")]
        public static void Rebuild()
        {
            if (Application.isPlaying) { Debug.LogWarning("[Arcade] Play 중에는 저장되지 않는다 — 종료 후 실행"); return; }

            var originalWall = FindSceneObject(WestWallPath);
            if (originalWall != null && !originalWall.activeSelf) originalWall.SetActive(true);
            var old = GameObject.Find("/" + RootName);
            if (old != null) Object.DestroyImmediate(old);
            var root = new GameObject(RootName);
            Undo.RegisterCreatedObjectUndo(root, "Arcade Room");

            var floorMat = Mat("ArcadeFloor", new Color(0.035f, 0.045f, 0.06f), 0.65f);
            var wallMat = Mat("ArcadeWall", new Color(0.48f, 0.46f, 0.44f), 0.28f);
            var ceilMat = Mat("ArcadeCeiling", new Color(0.20f, 0.055f, 0.075f), 0.26f);
            var neonMat = NeonMat("ArcadeNeon", new Color(0.12f, 0.58f, 0.76f), 1.25f);

            ClearExteriorObstacles();
            ClearEntranceSigns();
            
            PunchWestBoundary(root.transform);
PunchWestWall(root.transform, wallMat, neonMat);
            BuildCorridor(root.transform, floorMat, wallMat, ceilMat, neonMat);
            
            BuildInteriorFinish(root.transform, neonMat);
            // 오클루전은 개구부를 반영해 다시 구웠다(2026-09-19). 카메라 컬링을 임시로 끄던 우회 장치는 걷어냈다 —
            // 켜고 끄는 전환 자체가 입구에서 깜빡임으로 보였다.
BuildRoom(root.transform, floorMat, wallMat, ceilMat, neonMat);
            int placed = BuildCabinets(root.transform);
            PowerOnAllScreens();
            BuildLights(root.transform);

            EditorSceneManager_MarkDirty(root);
            Debug.Log($"[Arcade] 생성 완료 — 복도 {CorridorLen / M:F0} m · 방 {RoomDepth / M:F0}×{RoomWidth / M:F0} m · 캐비닛 {placed}대\n" +
                      $"방 범위 x {RoomWestX:F0}~{RoomEastX:F0} · z {RoomMinZ:F0}~{RoomMaxZ:F0}\n" +
                      "검증은 Festa/World/오락실 검증 으로 돌린다.");
        }

        /// <summary>배치 모드에서 실제 씬에 생성하고 저장한다.</summary>
        public static void RebuildBatch()
        {
            var scene = UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();
            Rebuild();
            UnityEditor.SceneManagement.EditorSceneManager.SaveScene(scene);
            AssetDatabase.SaveAssets();
            Debug.Log("[Arcade] main.unity 저장 완료");
        }

        /// <summary>배치 결과를 입구/실내 두 시점으로 렌더링해 시각 검수 자료를 만든다.</summary>
        public static void CaptureBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Capture("Builds/arcade-entrance.png", new Vector3(WestWallX + 8f * M, 2.1f * M, CorridorZ), new Vector3(RoomEastX, 1.4f * M, CorridorZ));
            Capture("Builds/arcade-room.png", new Vector3(RoomEastX - 2.2f * M, 2.2f * M, CorridorZ), new Vector3(RoomWestX + 4f * M, 1.1f * M, CorridorZ));
        }

        static void Capture(string relativePath, Vector3 position, Vector3 target)
        {
            var go = new GameObject("ArcadeCaptureCamera");
            var cam = go.AddComponent<Camera>();
            cam.transform.position = position;
            cam.transform.rotation = Quaternion.LookRotation(target - position, Vector3.up);
            cam.fieldOfView = 72f;
            cam.nearClipPlane = 0.1f;
            cam.farClipPlane = 700f;
            var rt = new RenderTexture(1280, 720, 24, RenderTextureFormat.ARGB32);
            cam.targetTexture = rt;
            cam.Render();
            RenderTexture.active = rt;
            var tex = new Texture2D(1280, 720, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, 1280, 720), 0, 0);
            tex.Apply();
            System.IO.File.WriteAllBytes(relativePath, tex.EncodeToPNG());
            RenderTexture.active = null;
            Object.DestroyImmediate(tex);
            Object.DestroyImmediate(rt);
            Object.DestroyImmediate(go);
            Debug.Log($"[Arcade] 캡처 저장: {relativePath}");
        }

        /// <summary>
        /// 기존 서쪽 벽 한 장을 끄고 같은 재질의 위·아래 구간으로 다시 세워 3.5 m 출입구를 만든다.
        /// 메시를 잘라 저장하지 않기 때문에 원본 벽은 그대로 남고 @Arcade를 지우면 복구할 수 있다.
        /// </summary>
        static void PunchWestWall(Transform parent, Material fallbackWall, Material neon)
        {
            var source = FindSceneObject(WestWallPath);
            if (source == null) { Debug.LogError($"[Arcade] 서쪽 벽을 찾지 못했다: {WestWallPath}"); return; }

            var sourceRenderer = source.GetComponent<Renderer>();
            var sourceCollider = source.GetComponent<Collider>();
            if (sourceRenderer == null && sourceCollider == null) { Debug.LogError("[Arcade] 서쪽 벽에 Renderer/Collider가 없다"); return; }
            Bounds b = sourceCollider != null ? sourceCollider.bounds : sourceRenderer.bounds;
            Material wall = sourceRenderer != null ? sourceRenderer.sharedMaterial : fallbackWall;
            Undo.RecordObject(source, "Open arcade entrance");
            source.SetActive(false);

            var g = new GameObject("EntranceWall"); g.transform.SetParent(parent, false);
            float gapMin = CorridorZ - CorridorWidth * 0.5f;
            float gapMax = CorridorZ + CorridorWidth * 0.5f;
            float minZ = b.min.z, maxZ = b.max.z;
            if (gapMin > minZ)
                Box(g.transform, "Wall_South", new Vector3(b.center.x, b.center.y, (minZ + gapMin) * 0.5f), new Vector3(b.size.x, b.size.y, gapMin - minZ), wall);
            
            float doorTop = CorridorHeight + 0.12f * M;
            if (doorTop < b.max.y)
                Box(g.transform, "Wall_Top", new Vector3(b.center.x, (doorTop + b.max.y) * 0.5f, CorridorZ),
                    new Vector3(b.size.x, b.max.y - doorTop, CorridorWidth), wall);
if (gapMax < maxZ)
                Box(g.transform, "Wall_North", new Vector3(b.center.x, b.center.y, (gapMax + maxZ) * 0.5f), new Vector3(b.size.x, b.size.y, maxZ - gapMax), wall);

            // 입구 프레임은 멀리서도 오락실을 찾게 하는 표지 역할을 겸한다.
            float frame = 0.10f * M;
            Box(g.transform, "Neon_Left", new Vector3(WestWallX + frame, CorridorHeight * 0.5f, gapMin), new Vector3(frame, CorridorHeight, frame), neon, false);
            Box(g.transform, "Neon_Right", new Vector3(WestWallX + frame, CorridorHeight * 0.5f, gapMax), new Vector3(frame, CorridorHeight, frame), neon, false);
            Box(g.transform, "Neon_Top", new Vector3(WestWallX + 0.56f * M, CorridorHeight, CorridorZ), new Vector3(frame, frame, CorridorWidth), neon, false);
            BuildEntranceSign(g.transform, neon);
        }

static void PunchWestBoundary(Transform parent)
        {
            var source = FindSceneObject("@WorldBoundary/West_W");
            if (source == null) { Debug.LogError("[Arcade] 서쪽 월드 경계를 찾지 못했다: @WorldBoundary/West_W"); return; }
            var sourceCollider = source.GetComponent<Collider>();
            if (sourceCollider == null) { Debug.LogError("[Arcade] 서쪽 월드 경계에 Collider가 없다"); return; }

            Bounds b = sourceCollider.bounds;
            Undo.RecordObject(source, "Open arcade world boundary");
            source.SetActive(false);

            var g = new GameObject("EntranceBoundary");
            g.transform.SetParent(parent, false);
            float gapMin = CorridorZ - CorridorWidth * 0.5f;
            float gapMax = CorridorZ + CorridorWidth * 0.5f;
            AddBoundarySegment(g.transform, "Boundary_South", b, b.min.z, gapMin);
            AddBoundarySegment(g.transform, "Boundary_North", b, gapMax, b.max.z);
        }

        static void AddBoundarySegment(Transform parent, string name, Bounds source, float minZ, float maxZ)
        {
            if (maxZ <= minZ) return;
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.position = new Vector3(source.center.x, source.center.y, (minZ + maxZ) * 0.5f);
            var box = go.AddComponent<BoxCollider>();
            box.size = new Vector3(source.size.x, source.size.y, maxZ - minZ);
            GameObjectUtility.SetStaticEditorFlags(go, StaticEditorFlags.BatchingStatic);
        }


static void BuildEntranceSign(Transform parent, Material neon)
        {
            var signMat = Mat("ArcadeSign", new Color(0.035f, 0.055f, 0.085f), 0.28f);
            float centerY = CorridorHeight + 0.72f * M;
            Box(parent, "ArcadeSignPlate",
                new Vector3(WestWallX + 0.60f * M, centerY, CorridorZ),
                new Vector3(0.12f * M, 0.92f * M, 4.3f * M), signMat, false);

            // 판 전체가 아니라 얇은 테두리만 네온으로 잡아 축제장 벽돌 조명과 어우러지게 한다.
            float edge = 0.055f * M;
            float signHalfW = 2.15f * M;
            float signHalfH = 0.46f * M;
            float faceX = WestWallX + 0.67f * M;
            Box(parent, "SignBorder_Top", new Vector3(faceX, centerY + signHalfH, CorridorZ), new Vector3(edge, edge, signHalfW * 2f), neon, false);
            Box(parent, "SignBorder_Bottom", new Vector3(faceX, centerY - signHalfH, CorridorZ), new Vector3(edge, edge, signHalfW * 2f), neon, false);
            Box(parent, "SignBorder_Left", new Vector3(faceX, centerY, CorridorZ - signHalfW), new Vector3(edge, signHalfH * 2f, edge), neon, false);
            Box(parent, "SignBorder_Right", new Vector3(faceX, centerY, CorridorZ + signHalfW), new Vector3(edge, signHalfH * 2f, edge), neon, false);

            var textGo = new GameObject("ArcadeSignText");
            textGo.transform.SetParent(parent, false);
            textGo.transform.position = new Vector3(WestWallX + 0.70f * M, centerY, CorridorZ);
            textGo.transform.rotation = Quaternion.Euler(0f, -90f, 0f);
            var text = textGo.AddComponent<TextMeshPro>();
            var koreanFont = Resources.Load<TMP_FontAsset>("Fonts/ChalkboardKR_SDF");
            if (koreanFont != null) text.font = koreanFont;
            text.text = "GAME STUDIO ARCADE\n게임 스튜디오 오락실";
            text.alignment = TextAlignmentOptions.Center;
            text.fontSize = 27f;
            text.fontStyle = FontStyles.Bold;
            text.color = new Color(0.62f, 0.93f, 1f);
            text.rectTransform.sizeDelta = new Vector2(54f, 14f);
            text.enableWordWrapping = false;
        }

        /// <summary>새 출입구를 가리는 기존 프로젝트 A형 안내판만 숨긴다.</summary>
        static void ClearEntranceSigns()
        {
            var center = new Vector3(WestWallX + 2.2f * M, 1.4f * M, CorridorZ);
            var half = new Vector3(2.2f * M, 1.4f * M, CorridorWidth * 0.42f);
            var disabled = new HashSet<GameObject>();
            foreach (var hit in Physics.OverlapBox(center, half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
            {
                Transform t = hit.transform, signRoot = null;
                while (t != null && t.parent != null)
                {
                    if (t.parent.name == "Festival_ProjectSigns") { signRoot = t; break; }
                    t = t.parent;
                }
                if (signRoot == null || !disabled.Add(signRoot.gameObject)) continue;
                Undo.RecordObject(signRoot.gameObject, "Clear arcade entrance");
                signRoot.gameObject.SetActive(false);
                Debug.Log($"[Arcade] 출입구 안내판 숨김: {PathOf(signRoot)}");
            }
        }

        /// <summary>증축 범위와 직접 겹치는 배경 나무만 숨긴다. 축제장 안쪽 오브젝트는 대상이 아니다.</summary>
        static void ClearExteriorObstacles()
        {
            float totalDepth = CorridorLen + RoomDepth;
            var center = new Vector3(WestWallX - totalDepth * 0.5f, RoomHeight * 0.5f, CorridorZ);
            var half = new Vector3(totalDepth * 0.5f, RoomHeight * 0.5f, RoomWidth * 0.5f);
            var disabled = new HashSet<GameObject>();
            foreach (var hit in Physics.OverlapBox(center, half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
            {
                Transform t = hit.transform, treeRoot = null;
                while (t != null && t.parent != null)
                {
                    if (t.parent.name == "Festival_Trees") { treeRoot = t; break; }
                    t = t.parent;
                }
                if (treeRoot == null || !disabled.Add(treeRoot.gameObject)) continue;
                Undo.RecordObject(treeRoot.gameObject, "Clear arcade extension");
                treeRoot.gameObject.SetActive(false);
                Debug.Log($"[Arcade] 증축 범위 나무 숨김: {PathOf(treeRoot)}");
            }
        }

        // ── 셸 ──────────────────────────────────────────────────

        static void BuildCorridor(Transform parent, Material floor, Material wall, Material ceil, Material neon)
        {
            var g = new GameObject("Corridor"); g.transform.SetParent(parent, false);
            float cx = WestWallX - CorridorLen * 0.5f;
            float halfW = CorridorWidth * 0.5f;

            Box(g.transform, "Floor", new Vector3(cx, -Thickness * 0.5f, CorridorZ), new Vector3(CorridorLen, Thickness, CorridorWidth), floor);
            Box(g.transform, "Ceiling", new Vector3(cx, CorridorHeight, CorridorZ), new Vector3(CorridorLen, Thickness, CorridorWidth), ceil);
            Box(g.transform, "Wall_North", new Vector3(cx, CorridorHeight * 0.5f, CorridorZ + halfW), new Vector3(CorridorLen, CorridorHeight, Thickness), wall);
            Box(g.transform, "Wall_South", new Vector3(cx, CorridorHeight * 0.5f, CorridorZ - halfW), new Vector3(CorridorLen, CorridorHeight, Thickness), wall);

            // 네온 띠 — 양 벽을 따라 안쪽으로 이어진다. 걸어 들어가는 방향을 눈으로 끌어 준다.
            Box(g.transform, "Neon_North", new Vector3(cx, CorridorHeight * 0.72f, CorridorZ + halfW - Thickness), new Vector3(CorridorLen * 0.92f, 0.08f * M, 0.06f * M), neon, collide: false);
            Box(g.transform, "Neon_South", new Vector3(cx, CorridorHeight * 0.72f, CorridorZ - halfW + Thickness), new Vector3(CorridorLen * 0.92f, 0.08f * M, 0.06f * M), neon, collide: false);
        }

        static void BuildRoom(Transform parent, Material floor, Material wall, Material ceil, Material neon)
        {
            var g = new GameObject("Room"); g.transform.SetParent(parent, false);
            float cx = (RoomEastX + RoomWestX) * 0.5f;

            Box(g.transform, "Floor", new Vector3(cx, -Thickness * 0.5f, CorridorZ), new Vector3(RoomDepth, Thickness, RoomWidth), floor);
            Box(g.transform, "Ceiling", new Vector3(cx, RoomHeight, CorridorZ), new Vector3(RoomDepth, Thickness, RoomWidth), ceil);
            Box(g.transform, "Wall_West", new Vector3(RoomWestX, RoomHeight * 0.5f, CorridorZ), new Vector3(Thickness, RoomHeight, RoomWidth), wall);
            Box(g.transform, "Wall_North", new Vector3(cx, RoomHeight * 0.5f, RoomMaxZ), new Vector3(RoomDepth, RoomHeight, Thickness), wall);
            Box(g.transform, "Wall_South", new Vector3(cx, RoomHeight * 0.5f, RoomMinZ), new Vector3(RoomDepth, RoomHeight, Thickness), wall);

            // 입구 벽 — 복도 폭만큼 비우고 위·아래 두 조각으로 나눈다. 통째로 세우면 못 들어온다.
            float gapHalf = CorridorWidth * 0.5f;
            float sideW = (RoomWidth - CorridorWidth) * 0.5f;
            Box(g.transform, "Wall_East_N", new Vector3(RoomEastX, RoomHeight * 0.5f, CorridorZ + gapHalf + sideW * 0.5f), new Vector3(Thickness, RoomHeight, sideW), wall);
            Box(g.transform, "Wall_East_S", new Vector3(RoomEastX, RoomHeight * 0.5f, CorridorZ - gapHalf - sideW * 0.5f), new Vector3(Thickness, RoomHeight, sideW), wall);
            Box(g.transform, "Wall_East_Top", new Vector3(RoomEastX, (CorridorHeight + RoomHeight) * 0.5f, CorridorZ), new Vector3(Thickness, RoomHeight - CorridorHeight, CorridorWidth), wall);
        }

static void BuildInteriorFinish(Transform parent, Material neon)
        {
            var g = new GameObject("InteriorFinish");
            g.transform.SetParent(parent, false);

            var tileDark = Mat("ArcadeTileDark", new Color(0.035f, 0.045f, 0.06f), 0.72f);
            var tileLight = Mat("ArcadeTileLight", new Color(0.70f, 0.72f, 0.72f), 0.78f);
            var bronze = Mat("ArcadeOrange", new Color(0.24f, 0.065f, 0.045f), 0.28f);
            var panel = Mat("ArcadePanel", new Color(0.66f, 0.64f, 0.61f), 0.34f);
            var backPanel = Mat("ArcadeBackPanel", Color.black, 0.08f);

            // 2 m 체크 타일 — 콜라이더 없이 기존 연속 바닥 위에 얹는다.
            const float tile = 2f * M;
            int nx = Mathf.CeilToInt(RoomDepth / tile);
            int nz = Mathf.CeilToInt(RoomWidth / tile);
            float startX = RoomWestX + RoomDepth / nx * 0.5f;
            float startZ = RoomMinZ + RoomWidth / nz * 0.5f;
            float sx = RoomDepth / nx;
            float sz = RoomWidth / nz;
            for (int x = 0; x < nx; x++)
            for (int z = 0; z < nz; z++)
                Box(g.transform, $"Tile_{x:00}_{z:00}",
                    new Vector3(startX + x * sx, 0.015f * M, startZ + z * sz),
                    new Vector3(sx * 0.985f, 0.03f * M, sz * 0.985f),
                    ((x + z) & 1) == 0 ? tileDark : tileLight, false);

            // 밝은 하부 벽 패널과 차분한 브론즈 띠가 긴 벽의 답답함을 끊는다.
            float cx = (RoomEastX + RoomWestX) * 0.5f;
            float panelY = 1.25f * M;
            float panelH = 2.2f * M;
            Box(g.transform, "Panel_North", new Vector3(cx, panelY, RoomMaxZ - 0.34f * M), new Vector3(RoomDepth * 0.94f, panelH, 0.08f * M), panel, false);
            Box(g.transform, "Panel_South", new Vector3(cx, panelY, RoomMinZ + 0.34f * M), new Vector3(RoomDepth * 0.94f, panelH, 0.08f * M), panel, false);
            Box(g.transform, "BronzeBand_North", new Vector3(cx, 2.42f * M, RoomMaxZ - 0.30f * M), new Vector3(RoomDepth * 0.96f, 0.16f * M, 0.10f * M), bronze, false);
            Box(g.transform, "BronzeBand_South", new Vector3(cx, 2.42f * M, RoomMinZ + 0.30f * M), new Vector3(RoomDepth * 0.96f, 0.16f * M, 0.10f * M), bronze, false);

            // 버건디 천장 아래에 브론즈 보만 둔다. 기존 청록 천장 띠는 보와 교차하며 Z-fighting을 일으켜 제거했다.
            for (int i = 0; i < 6; i++)
            {
                float t = (i + 0.5f) / 6f;
                float x = Mathf.Lerp(RoomEastX, RoomWestX, t);
                Box(g.transform, $"CeilingBeam_{i:00}", new Vector3(x, RoomHeight - 0.24f * M, CorridorZ),
                    new Vector3(0.18f * M, 0.16f * M, RoomWidth * 0.92f), bronze, false);
            }
            // 청록 레일은 보보다 0.18 m 아래, 좌우 통로 위에 둔다. 서로 다른 높이라 교차부에서도 Z-fighting이 없다.
            for (int side = -1; side <= 1; side += 2)
                Box(g.transform, $"CeilingGuide_{(side < 0 ? "South" : "North")}",
                    new Vector3(cx, RoomHeight - 0.42f * M, CorridorZ + side * 3.55f * M),
                    new Vector3(RoomDepth * 0.90f, 0.055f * M, 0.075f * M), neon, false);

            // 정면 초점은 무광 검정 패널과 컬러 로고로 잡는다.


            // 정면 초점은 무광 검정 패널과 단색 로고로 잡는다.
            Box(g.transform, "BackLogoPanel", new Vector3(RoomWestX + 0.34f * M, 2.5f * M, CorridorZ),
                new Vector3(0.10f * M, 1.60f * M, 5.8f * M), backPanel, false);

            var logoTexture = AssetDatabase.LoadAssetAtPath<Texture2D>("Assets/_Project/Art/World/Textures/SSAFestaLogoColor.png");
            if (logoTexture == null)
            {
                Debug.LogError("[Arcade] SSAFestaLogoColor.png를 찾지 못했다.");
                return;
            }

            const string logoMatPath = MatDir + "ArcadeLogoWhite.mat";
            var logoMat = AssetDatabase.LoadAssetAtPath<Material>(logoMatPath);
            if (logoMat == null)
            {
                logoMat = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
                AssetDatabase.CreateAsset(logoMat, logoMatPath);
            }
            logoMat.SetTexture("_BaseMap", logoTexture);
            logoMat.SetColor("_BaseColor", Color.white);
            logoMat.SetFloat("_Cull", 0f);
            EditorUtility.SetDirty(logoMat);

            var logo = GameObject.CreatePrimitive(PrimitiveType.Quad);
            logo.name = "SSAFESTA_Logo";
            logo.transform.SetParent(g.transform, false);
            logo.transform.position = new Vector3(RoomWestX + 0.405f * M, 2.5f * M, CorridorZ);
            logo.transform.rotation = Quaternion.Euler(0f, -90f, 0f);
            logo.transform.localScale = new Vector3(5.4f * M, 1.35f * M, 1f);
            logo.GetComponent<Renderer>().sharedMaterial = logoMat;
            Object.DestroyImmediate(logo.GetComponent<Collider>());
            GameObjectUtility.SetStaticEditorFlags(logo, StaticEditorFlags.BatchingStatic);
        }


        // ── 캐비닛 ──────────────────────────────────────────────

        /// <summary>
        /// 20대를 놓고 <c>arcade-01</c> … <c>arcade-20</c> 을 매긴다.
        ///
        /// <para>번호는 <b>가치 순</b>이다 — 섬 앞쪽(01~06) → 벽면 입구 쪽(07~14) → 안쪽(15~20).
        /// 사용자가 선착순으로 고르므로 번호가 낮을수록 좋은 자리여야 고를 때 설명이 필요 없다.</para>
        /// </summary>
        static int BuildCabinets(Transform parent)
        {
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(CabinetPrefab);
            if (prefab == null) { Debug.LogError($"[Arcade] 캐비닛 프리팹을 찾지 못했다: {CabinetPrefab}"); return 0; }

            var g = new GameObject("Cabinets"); g.transform.SetParent(parent, false);
            var spots = Spots();
            for (int i = 0; i < spots.Count; i++) Place(g.transform, prefab, spots[i], i + 1);
            return spots.Count;
        }

        struct Spot { public Vector3 pos; public float yaw; public Spot(Vector3 p, float y) { pos = p; yaw = y; } }

        /// <summary>자리 목록 — 이 순서가 곧 번호다.</summary>
static List<Spot> Spots()
        {
            var list = new List<Spot>();
            float wallFirstX = RoomEastX - EntranceClear;

            // 01~06 중앙 섬 — 뒤쪽 빈 공간을 채우되 뒷벽과 2.4 m를 두고 입구 시야는 비운다.
            float islandFirstX = RoomWestX + 2.4f * M;
            for (int i = 0; i < IslandRowCount; i++)
            {
                float x = islandFirstX + i * CabinetPitch;
                list.Add(new Spot(new Vector3(x, 0f, CorridorZ + IslandHalfGap), 0f));
                list.Add(new Spot(new Vector3(x, 0f, CorridorZ - IslandHalfGap), 180f));
            }

            // 07~20 벽면 — 입구에서 가까운 것부터 북·남 번갈아 배치한다.
            for (int i = 0; i < WallRowCount; i++)
            {
                float x = wallFirstX - i * CabinetPitch;
                list.Add(new Spot(new Vector3(x, 0f, RoomMaxZ - WallInset), 180f));
                list.Add(new Spot(new Vector3(x, 0f, RoomMinZ + WallInset), 0f));
            }
            return list;
        }

        static void Place(Transform parent, GameObject prefab, Spot spot, int number)
        {
            var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab, parent);
            go.name = $"Arcade_{number:00}";
            go.transform.position = spot.pos;
            
            // 프리팹은 미터 단위(높이 1.97)지만 축제 월드는 1 m = 13.26 units다.
            go.transform.localScale = Vector3.one * M;
go.transform.rotation = Quaternion.Euler(0f, spot.yaw, 0f);

            var machine = go.GetComponent<Festa.Content.Arcade.ArcadeMachineInteractable>();
            if (machine == null) machine = go.AddComponent<Festa.Content.Arcade.ArcadeMachineInteractable>();

            // machineId 는 직렬화 필드라 SerializedObject 로 쓴다 — 프리팹 인스턴스에 오버라이드로 남는다.
            var so = new SerializedObject(machine);
            var prop = so.FindProperty("_machineId");
            if (prop != null) { prop.stringValue = $"arcade-{number:00}"; so.ApplyModifiedPropertiesWithoutUndo(); }
            else Debug.LogWarning($"[Arcade] {go.name}: _machineId 필드를 찾지 못했다 — 이름이 바뀌었는지 확인해라.");
        }

        // ── 조명 ────────────────────────────────────────────────

        /// <summary>
        /// 실시간 점광원은 <b>네 개만</b> 쓴다. WebGL 실측에서 병목이 드로우콜 제출이었고(아바타 40기 990콜 25.7 FPS),
        /// 광원을 늘리면 거기에 그대로 얹힌다. 밝기는 축제존보다 한 단계 낮추되 캐비닛을 읽을 수 있는 선을 지킨다 —
        /// 어두우면 어느 기계에 뭐가 걸렸는지 멀리서 안 보여 하나하나 다가가야 한다.
        /// </summary>
        static void BuildLights(Transform parent)
        {
            var g = new GameObject("Lights"); g.transform.SetParent(parent, false);
            for (int i = 0; i < 4; i++)
            {
                float t = (i + 0.5f) / 4f;
                float x = Mathf.Lerp(RoomEastX, RoomWestX, t);
                var go = new GameObject($"Light_{i:00}");
                go.transform.SetParent(g.transform, false);
                go.transform.position = new Vector3(x, RoomHeight - 0.35f * M, CorridorZ);
                var light = go.AddComponent<Light>();
                light.type = LightType.Point;
                light.range = 9f * M;
                // Unity 6 물리 광량 단위 기준. 1~2는 사실상 암실이라 축제존 수준 가독성이 나오지 않는다.
                light.intensity = 3000f;
                light.color = (i % 2 == 0) ? new Color(1f, 0.62f, 0.34f) : new Color(0.72f, 0.90f, 1f);
                light.shadows = LightShadows.None;   // 그림자까지 켜면 캐비닛 20대가 그림자 패스를 한 번 더 탄다
            }
        }

        // ── 도우미 ──────────────────────────────────────────────

        static GameObject Box(Transform parent, string name, Vector3 center, Vector3 size, Material mat, bool collide = true)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.position = center;
            go.transform.localScale = size;
            go.GetComponent<Renderer>().sharedMaterial = mat;
            if (!collide) Object.DestroyImmediate(go.GetComponent<Collider>());
            GameObjectUtility.SetStaticEditorFlags(go, StaticEditorFlags.BatchingStatic);
            return go;
        }

static Material Mat(string name, Color color, float smoothness)
        {
            var path = MatDir + name + ".mat";
            var existing = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (existing != null)
            {
                existing.SetColor("_BaseColor", color);
                existing.SetFloat("_Smoothness", smoothness);
                EditorUtility.SetDirty(existing);
                return existing;
            }
            var mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            mat.SetColor("_BaseColor", color);
            mat.SetFloat("_Smoothness", smoothness);
            AssetDatabase.CreateAsset(mat, path);
            return mat;
        }

        /// <summary>네온은 광원이 아니라 <b>이미시브 재질</b>이다 — 색은 나오고 광원 비용은 0 이다.</summary>
static Material NeonMat(string name, Color color, float intensity)
        {
            var path = MatDir + name + ".mat";
            var existing = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (existing != null)
            {
                existing.SetColor("_BaseColor", color * intensity);
                EditorUtility.SetDirty(existing);
                return existing;
            }
            var mat = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
            mat.SetColor("_BaseColor", color * intensity);
            AssetDatabase.CreateAsset(mat, path);
            return mat;
        }

        static void EditorSceneManager_MarkDirty(GameObject go) =>
            UnityEditor.SceneManagement.EditorSceneManager.MarkSceneDirty(go.scene);

        // ── 검증 ────────────────────────────────────────────────

        const string MainScene = "Assets/_Project/Scenes/main.unity";

        /// <summary>
        /// 배치 모드 검증 — 씬을 열어 <b>지을 자리가 비어 있는지</b> 보고, 지어 본 뒤 결과를 센다. 저장하지 않는다.
        ///
        /// <para>빈 자리 확인이 먼저인 이유: 축제장 서쪽 벽 바깥은 비어 있을 것으로 보지만 그건 추정이다.
        /// 무언가 이미 있으면 방이 그 위에 겹쳐 지어지고, 그 사실은 사람이 그 자리까지 걸어가야 드러난다.</para>
        /// </summary>
        public static void AuditBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();

            // ① 지을 자리가 비어 있나 — 복도와 방을 덮는 상자로 검사한다.
            var roomCenter = new Vector3((RoomEastX + RoomWestX) * 0.5f, RoomHeight * 0.5f, CorridorZ);
            var roomHalf = new Vector3(RoomDepth, RoomHeight, RoomWidth) * 0.5f;
            var corridorCenter = new Vector3(WestWallX - CorridorLen * 0.5f, CorridorHeight * 0.5f, CorridorZ);
            var corridorHalf = new Vector3(CorridorLen, CorridorHeight, CorridorWidth) * 0.5f;

            int blocked = 0;
            foreach (var pair in new[] { ("방", roomCenter, roomHalf), ("복도", corridorCenter, corridorHalf) })
                foreach (var hit in Physics.OverlapBox(pair.Item2, pair.Item3, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
                {
                    blocked++;
                    Debug.LogWarning($"[ArcadeAudit] {pair.Item1} 자리에 이미 무언가 있다: '{hit.name}'");
                }

            // ② 지어 보고 결과를 센다.
            Rebuild();
            var root = GameObject.Find("/" + RootName);
            if (root == null) { Debug.LogError("[ArcadeAudit] 루트를 만들지 못했다"); return; }

            var machines = root.GetComponentsInChildren<Festa.Content.Arcade.ArcadeMachineInteractable>(true);
            var ids = new HashSet<string>();
            int dup = 0, empty = 0;
            foreach (var m in machines)
            {
                if (string.IsNullOrWhiteSpace(m.MachineId)) { empty++; continue; }
                if (!ids.Add(m.MachineId)) dup++;
            }

            // ③ 통로 폭 — 섬과 벽 줄 사이가 사람 둘이 지나갈 만한가.
            float aisle = (RoomWidth * 0.5f - WallInset) - IslandHalfGap;
            Debug.Log($"[ArcadeAudit] 자리 간섭 {blocked} · 캐비닛 {machines.Length}대 · 고유 id {ids.Count} · 중복 {dup} · 빈 id {empty}\n" +
                      $"통로 폭 {aisle / M:F2} m · 방 {RoomDepth / M:F0}×{RoomWidth / M:F0} m · 천장 {RoomHeight / M:F1} m\n" +
                      $"방 x {RoomWestX:F0}~{RoomEastX:F0} · z {RoomMinZ:F0}~{RoomMaxZ:F0} (축제장 서쪽 벽 {WestWallX})");
        }

        /// <summary>
        /// 지을 자리를 찾는다 — 복도 중심 z 를 훑어 <b>아무것도 물지 않는</b> 위치를 고른다.
        ///
        /// <para>처음에 z 140(기존 오락기 줄과 같은 높이)에 두려 했는데 그 바깥에 가문비나무가 서 있었다.
        /// 스폰 격자 중심을 정할 때와 같은 방법을 쓴다 — 후보를 전부 훑어 조건을 만족하는 곳 중
        /// 원래 자리에 가장 가까운 것을 고른다. 눈으로 찍으면 다음에 또 같은 걸 밟는다.</para>
        ///
        /// <para>축제장 서쪽 벽(<c>Wall_West</c>)은 세지 않는다 — 복도가 그 벽을 지나는 것이 설계다.</para>
        /// </summary>
        public static void ScanBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();

            var report = new System.Text.StringBuilder();
            float bestZ = float.NaN; int bestCount = int.MaxValue; float bestDist = float.MaxValue;

            for (float z = -20f; z <= 300f; z += 10f)
            {
                int n = BlockersAt(z, out var names);
                float dist = Mathf.Abs(z - 140f);
                if (n < bestCount || (n == bestCount && dist < bestDist)) { bestCount = n; bestZ = z; bestDist = dist; }
                if (n > 0) report.AppendLine($"  z {z,6:F0} — 간섭 {n,3} ({names})");
                else report.AppendLine($"  z {z,6:F0} — 비어 있음");
            }

            Debug.Log($"[ArcadeScan] 최적 복도 중심 z = {bestZ:F0} (간섭 {bestCount})\n{report}");
        }

        /// <summary>그 z 에 복도+방을 놓았을 때 걸리는 콜라이더 수. 축제장 서쪽 벽은 제외한다.</summary>
        static int BlockersAt(float z, out string names)
        {
            var roomCenter = new Vector3((RoomEastX + RoomWestX) * 0.5f, RoomHeight * 0.5f, z);
            var roomHalf = new Vector3(RoomDepth, RoomHeight, RoomWidth) * 0.5f;
            var corridorCenter = new Vector3(WestWallX - CorridorLen * 0.5f, CorridorHeight * 0.5f, z);
            var corridorHalf = new Vector3(CorridorLen, CorridorHeight, CorridorWidth) * 0.5f;

            var found = new HashSet<string>();
            int count = 0;
            foreach (var pair in new[] { (roomCenter, roomHalf), (corridorCenter, corridorHalf) })
                foreach (var hit in Physics.OverlapBox(pair.Item1, pair.Item2, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
                {
                    if (hit.name.StartsWith("Wall_West")) continue;   // 뚫고 지나갈 벽이라 간섭이 아니다
                    count++;
                    if (found.Count < 4) found.Add(hit.name);
                }
            names = string.Join(", ", found);
            return count;
        }

        /// <summary>
        /// 축제장 서쪽 바깥을 측량한다 — 무엇이 어디에 얼마나 있는지.
        ///
        /// <para>z 를 훑는 것만으로는 "전부 막혔다" 까지만 알 수 있었다. 어디로 옮겨야 하는지 정하려면
        /// 막은 것들의 <b>실제 위치와 크기</b>가 필요하다. LOD 단계는 같은 나무를 네 번 세므로
        /// 최상위 부모로 묶어 한 그루로 센다.</para>
        /// </summary>
        public static void SurveyBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();

            // 서쪽 벽에서 서쪽으로 40 m, 남북은 축제장 전체 폭.
            var center = new Vector3(WestWallX - 20f * M, 15f * M, 140f);
            var half = new Vector3(20f * M, 20f * M, 200f);

            var groups = new Dictionary<string, Bounds>();
            foreach (var hit in Physics.OverlapBox(center, half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
            {
                var root = hit.transform;
                while (root.parent != null && root.parent.name != "Environment" && root.parent.parent != null) root = root.parent;
                var key = root.name;
                var b = hit.bounds;
                if (groups.TryGetValue(key, out var existing)) { existing.Encapsulate(b); groups[key] = existing; }
                else groups[key] = b;
            }

            var sb = new System.Text.StringBuilder();
            foreach (var kv in groups)
                sb.AppendLine($"  {kv.Key,-34} 중심 ({kv.Value.center.x,7:F0}, {kv.Value.center.y,6:F0}, {kv.Value.center.z,7:F0})  크기 ({kv.Value.size.x,6:F0} × {kv.Value.size.y,6:F0} × {kv.Value.size.z,6:F0})");

            // 바닥이 어디까지 있나 — 방을 새로 놓을 자리에 바닥이 있는지 본다.
            var floor = new System.Text.StringBuilder();
            for (float x = WestWallX; x >= WestWallX - 40f * M; x -= 5f * M)
            {
                bool hitGround = Physics.Raycast(new Vector3(x, 30f * M, 140f), Vector3.down, out var h, 60f * M);
                floor.AppendLine($"  x {x,7:F0} — 바닥 {(hitGround ? $"있음 y={h.point.y:F1} ({h.collider.name})" : "없음")}");
            }

            Debug.Log($"[ArcadeSurvey] 서쪽 바깥 물체 {groups.Count}종\n{sb}\n바닥 탐색 (z=140)\n{floor}");
        }

        /// <summary>
        /// 축제장 <b>안쪽</b>에서 방이 들어갈 빈 자리를 찾는다.
        ///
        /// <para>서쪽 바깥은 바닥조차 없는 허공이라(SurveyBatch) 밖으로 나가면 지면부터 지어야 한다.
        /// 안쪽은 바닥·벽·천장이 이미 있으므로, 비어 있는 사각형만 찾으면 구획만으로 방이 된다.</para>
        ///
        /// <para>바닥은 세지 않는다(y 0.3 m 위부터 본다) — 바닥에 닿는 것은 막힘이 아니라 서 있을 자리다.</para>
        /// </summary>
        public static void InteriorScanBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();

            float yLow = 0.3f * M, yHigh = RoomHeight;
            var half = new Vector3(RoomDepth * 0.5f, (yHigh - yLow) * 0.5f, RoomWidth * 0.5f);
            var candidates = new List<InteriorCandidate>();
            int clear = 0;

            // 축제장 벽 안쪽은 x −930~−220 · z −40~330 이다(FestivalMinimapArea). 방 중심이 그 안에
            // 온전히 들어오는 범위만 훑는다 — 중심을 벽 가까이 두면 방이 벽을 뚫고 나간다.
            float xFrom = -930f + RoomDepth * 0.5f, xTo = -220f - RoomDepth * 0.5f;
            float zFrom = -40f + RoomWidth * 0.5f, zTo = 330f - RoomWidth * 0.5f;
            for (float x = xFrom; x <= xTo; x += 25f)
            for (float z = zFrom; z <= zTo; z += 25f)
            {
                var c = new Vector3(x, (yLow + yHigh) * 0.5f, z);
                var names = new HashSet<string>();
                int n = 0;
                foreach (var hit in Physics.OverlapBox(c, half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
                { n++; if (names.Count < 3) names.Add(hit.name); }
                if (n == 0) clear++;
                candidates.Add(new InteriorCandidate(x, z, n, string.Join(", ", names)));
            }

            candidates.Sort((a, b) => a.count != b.count ? a.count.CompareTo(b.count) :
                Vector2.Distance(new Vector2(a.x, a.z), new Vector2(-760f, 140f))
                    .CompareTo(Vector2.Distance(new Vector2(b.x, b.z), new Vector2(-760f, 140f))));
            var sb = new System.Text.StringBuilder();
            for (int i = 0; i < Mathf.Min(20, candidates.Count); i++)
            {
                var c = candidates[i];
                sb.AppendLine($"  #{i + 1:00} x {c.x,6:F0} z {c.z,6:F0} — 간섭 {c.count,3} ({c.names})");
            }

            Debug.Log($"[ArcadeInterior] 방 {RoomDepth / M:F0}×{RoomWidth / M:F0} m 가 들어갈 빈 자리 {clear}곳 " +
                      $"(훑은 범위 x {xFrom:F0}~{xTo:F0} · z {zFrom:F0}~{zTo:F0})\n{sb}");
        }

        readonly struct InteriorCandidate
        {
            public readonly float x, z;
            public readonly int count;
            public readonly string names;
            public InteriorCandidate(float x, float z, int count, string names)
            { this.x = x; this.z = z; this.count = count; this.names = names; }
        }

        /// <summary>선정한 남서쪽 후보의 실제 충돌물과 bounds를 출력한다.</summary>
        public static void InteriorDetailBatch()
        {
            UnityEditor.SceneManagement.EditorSceneManager.OpenScene(MainScene, UnityEditor.SceneManagement.OpenSceneMode.Single);
            Physics.SyncTransforms();
            const float x = -756f, z = 108f;
            float yLow = 0.3f * M, yHigh = RoomHeight;
            var center = new Vector3(x, (yLow + yHigh) * 0.5f, z);
            var half = new Vector3(RoomDepth * 0.5f, (yHigh - yLow) * 0.5f, RoomWidth * 0.5f);
            foreach (var hit in Physics.OverlapBox(center, half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
            {
                var b = hit.bounds;
                Debug.Log($"[ArcadeDetail] {hit.name} path={PathOf(hit.transform)} center=({b.center.x:F1},{b.center.y:F1},{b.center.z:F1}) size=({b.size.x:F1},{b.size.y:F1},{b.size.z:F1})");
            }
        }

        static string PathOf(Transform t)
        {
            var parts = new List<string>();
            while (t != null) { parts.Add(t.name); t = t.parent; }
            parts.Reverse();
            return string.Join("/", parts);
        }

        static GameObject FindSceneObject(string path)
        {
            foreach (var go in Resources.FindObjectsOfTypeAll<GameObject>())
            {
                if (!go.scene.IsValid()) continue;
                if (PathOf(go.transform) == path) return go;
            }
            return null;
        }
    

/// <summary>
        /// 씬의 모든 게임기 화면을 켠다 — 오락실 20대와 광장에 원래 있던 기계까지 함께.
        ///
        /// <para>모델의 화면 슬롯은 조명을 받는 회색 Lit 머티리얼이라 실내가 어두우면 전원이 꺼진 기계로 읽힌다.
        /// 어떤 기계에 어떤 게임이 걸렸는지는 FE 가 정하므로(spec 019 FR-017) Unity 는 화면 내용을 모른다 —
        /// 지금은 전부 어트랙트 화면으로 켜 두고, 매핑 계약(GitLab #256)이 붙으면
        /// <see cref="Festa.Content.Arcade.ArcadeScreenPower.SetPowered"/> 로 기계별로 가른다.</para>
        /// </summary>
[MenuItem("Festa/World/게임기 화면 켜기")]
        public static void PowerOnAllScreens()
        {
            var on = ScreenOnMaterial();
            if (on == null) return;

            var done = new HashSet<GameObject>();
            int count = 0;

            // ① 상호작용하는 게임기 — 컴포넌트를 루트에 붙여 FE 매핑이 붙으면 기계별로 끄고 켤 수 있게 한다.
            foreach (var machine in Object.FindObjectsByType<Festa.Content.Arcade.ArcadeMachineInteractable>(
                         FindObjectsInactive.Include, FindObjectsSortMode.None))
                if (done.Add(machine.gameObject) && PowerOn(machine.gameObject, on)) count++;

            // ② 전시용으로만 놓인 캐비닛(관리 부스 등)도 켠다 — 한 대만 까맣게 남으면 고장으로 읽힌다.
            foreach (var renderer in Object.FindObjectsByType<MeshRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                bool hasScreen = false;
                foreach (var mat in renderer.sharedMaterials)
                    if (mat != null && mat.name.StartsWith(Festa.Content.Arcade.ArcadeScreenPower.ScreenMaterialName)) { hasScreen = true; break; }
                if (!hasScreen) continue;

                var target = renderer.transform.parent != null ? renderer.transform.parent.gameObject : renderer.gameObject;
                if (done.Add(target) && PowerOn(target, on)) count++;
            }

            Debug.Log($"[Arcade] 게임기 화면 {count}대를 켰다 (머티리얼 {ScreenOnPath}).");
        }

        static bool PowerOn(GameObject target, Material on)
        {
            var power = target.GetComponent<Festa.Content.Arcade.ArcadeScreenPower>();
            if (power == null) power = Undo.AddComponent<Festa.Content.Arcade.ArcadeScreenPower>(target);
            power.EditorSetup(on);
            EditorUtility.SetDirty(power);
            return true;
        }

        const string ScreenOnPath = "Assets/_Project/Art/World/Materials/ArcadeScreenOn.mat";
        const string AttractTexturePath = "Assets/_Project/Art/World/Textures/ArcadeAttractScreen.png";

        /// <summary>켜진 화면 머티리얼. 캐비닛 전체가 <b>하나를 공유</b>해 드로우콜이 늘지 않는다.</summary>
        static Material ScreenOnMaterial()
        {
            var tex = AssetDatabase.LoadAssetAtPath<Texture2D>(AttractTexturePath);
            if (tex == null)
            {
                Debug.LogError($"[Arcade] 어트랙트 화면 텍스처를 찾지 못했다: {AttractTexturePath}");
                return null;
            }

            var mat = AssetDatabase.LoadAssetAtPath<Material>(ScreenOnPath);
            if (mat == null)
            {
                // 화면은 스스로 빛나는 물건이다. Unlit 이면 실내 밝기와 무관하게 늘 켜져 보이고 광원 비용도 0 이다.
                mat = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
                AssetDatabase.CreateAsset(mat, ScreenOnPath);
            }
            mat.SetTexture("_BaseMap", tex);
            mat.SetColor("_BaseColor", Color.white);
            EditorUtility.SetDirty(mat);
            return mat;
        }
}
}

