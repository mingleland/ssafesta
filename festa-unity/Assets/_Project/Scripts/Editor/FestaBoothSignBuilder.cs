using System.Collections.Generic;
using System.IO;
using Festa.World;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.EditorTools
{
    /// <summary>
    /// 축제장 부스 12칸 <b>앞 왼쪽에 표지판</b>을 세우고 프로젝트명을 적는다 (GitLab #171).
    ///
    /// <para><b>처음에는 지붕 위에 마퀴를 얹었다가 걷어냈다</b> (2026-09-10 사용자 판단 —
    /// "이건 좀 그냥 없는게 나은 수준"). 폭 30~78 짜리 검정 판 12개가 부스보다 눈에 먼저 띄고
    /// 하늘을 다 가려서, 부스를 꾸며 주는 게 아니라 부스를 가리는 물건이 됐다. 지금은 카니발 킷의
    /// <c>PF_A_Frame_Sign</c>(입간판)을 부스 앞에 세운다 — 있던 에셋이고, 축제장 눈높이에 맞고,
    /// 판이 이미 글씨 쓰라고 비어 있다.</para>
    ///
    /// <para><b>왼쪽 앞.</b> 통로에서 부스를 바라보는 사람 기준의 왼쪽이다 — 북쪽 줄은 −x,
    /// 남쪽 줄은 +x 로 갈린다(두 줄이 서로 마주 본다). 부스 정면을 가리지 않게 <b>폭 바깥</b>에 둔다.</para>
    ///
    /// <para><b>손으로 옮긴 자리를 이긴다.</b> 위 계산은 <b>처음 세울 때만</b> 쓰고, 이미 표지판이
    /// 있으면 그 자리를 그대로 물려받는다. 이 빌더는 루트를 통째로 지우고 다시 짓기 때문에
    /// 그러지 않으면 씬에서 맞춰 둔 자리가 매번 날아간다 (2026-09-10 — 12칸을 전부 옮겨 둔 것을
    /// 날릴 뻔했다). 계산값으로 되돌리려면 "부스 표지판 위치 초기화" 메뉴를 쓴다.</para>
    ///
    /// <para><b>병합 대상이 아니다.</b> 루트 <c>@Festival/Festival_ProjectSigns</c> 는
    /// <c>FestivalStaticCombiner.Groups</c> 에 없다 — 들어가면 런타임에 글자를 못 바꾸고 유령이 남는다(T-254).
    /// 정적 플래그도 전부 0 이다(T-253).</para>
    /// </summary>
    public static class FestaBoothSignBuilder
    {
        const string RootName = "Festival_ProjectSigns";
        const string MatDir = "Assets/_Project/Art/Materials/Generated";
        const string SignPrefab = "Assets/_Project/Art/Booth/CarnivalKit/Prefabs/PF_A_Frame_Sign.prefab";

        /// <summary>통로 z. 이 값보다 북쪽에 있는 칸이 "북쪽 줄"이다 (실측: 북 z≈230 · 남 z≈60).</summary>
        const float AisleZ = 145f;

        /// <summary>
        /// 입간판 배율. 부스 프롭이 쓰는 월드 배율은 13.26 이고(원본 0.97 m → 12.9 u), 그대로 두면
        /// 무릎 높이라 글자가 안 읽힌다. 15 면 판 높이 14.5 u ≈ 1.1 m — 사람(22.4 u) 허리께다.
        ///
        /// <para><b>26 까지 키웠다가 되돌렸다</b> (2026-09-10 — "저러면 부스를 가리잖니"). 사람보다 큰
        /// 입간판은 부스 정면을 통째로 가린다. 멀리서 읽히는 것과 안 가리는 것은 같이 못 가지며,
        /// 멀리서 읽히는 쪽(지붕 마퀴)은 이미 한 번 걷어냈다. 입간판은 <b>다가와서 읽는 물건</b>이다.</para>
        /// </summary>
        const float SignScale = 15f;

        /// <summary>
        /// 부스 <b>폭 바깥</b>으로 이만큼 더 나간 자리에 세운다. 전에는 반폭의 62% 지점이라
        /// 부스 안쪽이었고, 그래서 정면을 가렸다. 부스 사이 간격이 40 u 이상이라 여유가 있다.
        /// </summary>
        const float SideMargin = 7f;
        const float FrontGap = 5f;       // 부스 앞면에서 통로 쪽으로 살짝. 길 한복판에 두지 않는다.

        const float CardSize = 22f;
        const float CardLift = 18f;      // 그 부스 지붕 위로

        [MenuItem("Festa/World/부스 표지판·전시 카드 생성 — #171")]
        public static void Build()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[BoothSign] 플레이 모드에서는 돌리지 않는다."); return; }

            var festival = GameObject.Find("@Festival");
            if (festival == null) { Debug.LogError("[BoothSign] @Festival 이 없다."); return; }

            // **학교안심 칠판지우개**를 쓴다 (사용자 지정 2026-09-10). 분필로 쓴 글씨체라
            // 칠판 입간판과 결이 맞는다. 없으면 주아 → NotoSansKR Bold 순으로 물러난다.
            var font = Resources.Load<TMP_FontAsset>("Fonts/ChalkboardKR_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/Jua_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font == null) { Debug.LogError("[BoothSign] Resources/Fonts 에 쓸 글꼴이 없다"); return; }

            var signPrefab = AssetDatabase.LoadAssetAtPath<GameObject>(SignPrefab);
            if (signPrefab == null) { Debug.LogError($"[BoothSign] {SignPrefab} 없음"); return; }

            // 카드 바탕은 어둡게 둔다 — 썸네일이 붙으면 BoothSign.ShowThumbnail 이 흰색으로 올린다.
            var cardMat = Material("M_BoothSign_Card", new Color(0.07f, 0.09f, 0.16f), new Color(0.10f, 0.13f, 0.22f), 1f, 0.5f);
            var cardRimMat = Material("M_BoothSign_CardRim", new Color(0.36f, 0.78f, 0.98f), new Color(0.45f, 0.95f, 1.3f), 1f, 0.4f);
            // **칠판**이다. 처음엔 밝은 종이판이었는데 "흰 배경에 딱딱한 폰트라 별로" 라는 지적을
            // 받고 짙은 슬레이트로 바꿨다 (2026-09-10, 사용자가 올린 카페 입간판 사진 기준).
            // 발광은 아주 약하게만 — 밤에 글자가 읽히는 정도면 되고, 세게 주면 12개가 등불이 된다.
            var plateMat = Material("M_BoothSign_Plate", new Color(0.105f, 0.125f, 0.118f), new Color(0.10f, 0.13f, 0.12f), 0.30f, 0.22f);
            // 판 안쪽에 도는 얇은 흰 테두리 — 레퍼런스 사진의 그 선이다.
            var chalkMat = Material("M_BoothSign_Chalk", new Color(0.88f, 0.89f, 0.84f), new Color(0.55f, 0.57f, 0.53f), 0.5f, 0.1f);
            // 전시 이미지가 붙는 면. 런타임에 BoothSign.ShowThumbnail 이 면마다 인스턴스를 떠서 텍스처를 넣는다.
            var photoMat = Material("M_BoothSign_Photo", Color.white, Color.black, 0f, 0.05f);

            var old = festival.transform.Find(RootName);

            // **손으로 옮긴 자리를 기억한다.** 이 빌더는 루트를 통째로 지우고 다시 짓기 때문에,
            // 씬에서 표지판을 옮겨 두면 다음에 돌릴 때 계산값으로 되돌아간다 — 실제로 12칸을
            // 전부 옮겨 놓은 것을 날릴 뻔했다 (2026-09-10). 사람이 정한 자리가 계산값보다 낫다.
            // 처음부터 다시 잡고 싶으면 아래 "위치 초기화" 메뉴를 쓴다.
            var kept = new Dictionary<int, (Vector3 pos, Quaternion rot)>();
            if (old != null)
            {
                foreach (Transform child in old)
                    if (TryParseSlot(child.name, out int n))
                        kept[n] = (child.position, child.rotation);
                Object.DestroyImmediate(old.gameObject);
            }

            var root = new GameObject(RootName);
            root.transform.SetParent(festival.transform, false);

            int built = 0, missing = 0, restored = 0;
            for (int slot = 1; slot <= BoothSignPresenter.SlotCount; slot++)
            {
                var body = FindSlotBody(festival.transform, slot);
                if (body == null) { missing++; Debug.LogWarning($"[BoothSign] 슬롯 {slot:00} 실물을 못 찾았다 — 건너뛴다."); continue; }

                var sign = BuildOne(root.transform, slot, body.bounds, font, signPrefab, cardMat, cardRimMat, plateMat, chalkMat, photoMat);
                built++;

                if (kept.TryGetValue(slot, out var place))
                {
                    sign.transform.SetPositionAndRotation(place.pos, place.rot);
                    restored++;
                }
            }

            EditorSceneManager.MarkSceneDirty(SceneManager.GetActiveScene());
            EditorSceneManager.SaveOpenScenes();
            Debug.Log($"[BoothSign] 표지판 {built}칸 생성" + (missing > 0 ? $" (실물 미확인 {missing}칸)" : "") +
                      (restored > 0 ? $" · 기존 자리 {restored}칸 유지" : "") +
                      $" — 루트 @Festival/{RootName}. 값은 런타임에 BoothSignPresenter 가 채운다.");
        }

        /// <summary>
        /// 손으로 옮긴 자리를 버리고 <b>계산값으로 되돌린다.</b> 부스를 옮겼거나 배치를 처음부터
        /// 다시 잡을 때만 쓴다 — 평소 빌드는 기존 자리를 유지한다.
        /// </summary>
        [MenuItem("Festa/World/부스 표지판 위치 초기화 (손으로 옮긴 자리 버림)")]
        public static void ResetPlacement()
        {
            if (EditorApplication.isPlaying) { Debug.LogError("[BoothSign] 플레이 모드에서는 돌리지 않는다."); return; }

            var festival = GameObject.Find("@Festival");
            var old = festival != null ? festival.transform.Find(RootName) : null;
            if (old == null) { Debug.LogWarning("[BoothSign] 표지판이 없다 — 생성 메뉴를 먼저 돌려라."); return; }

            if (!EditorUtility.DisplayDialog("부스 표지판 위치 초기화",
                    "씬에서 손으로 옮긴 표지판 자리를 버리고 계산값으로 되돌립니다. 되돌릴 수 없습니다.",
                    "초기화", "취소")) return;

            Object.DestroyImmediate(old.gameObject);
            Build();   // 기억할 것이 없으니 계산값으로 새로 선다
        }

        static bool TryParseSlot(string objectName, out int slot)
            => int.TryParse(objectName.Replace("BoothSign_", string.Empty), out slot);

        /// <summary>
        /// 그 슬롯의 실물 렌더러. 병합본(<c>FestivalSlot_NN_Combined</c>)이 있으면 그것이 정답이다 —
        /// 원본 렌더러는 병합 후 꺼져 있어 <c>bounds</c> 가 갱신되지 않는다.
        /// </summary>
        static Renderer FindSlotBody(Transform festival, int slot)
        {
            var slots = festival.Find("Festival_Slots");
            var t = slots != null ? slots.Find($"FestivalSlot_{slot:00}") : null;
            if (t == null) return null;

            var combined = t.Find($"FestivalSlot_{slot:00}_Combined");
            if (combined != null)
            {
                var r = combined.GetComponent<Renderer>();
                if (r != null) return r;
            }
            foreach (var r in t.GetComponentsInChildren<Renderer>(true))
                if (r.enabled && r.gameObject.activeInHierarchy) return r;
            return null;
        }

        static GameObject BuildOne(Transform parent, int slot, Bounds b, TMP_FontAsset font,
                             GameObject signPrefab, Material cardMat, Material cardRimMat, Material plateMat, Material chalkMat, Material photoMat)
        {
            bool northRow = b.center.z > AisleZ;

            // 통로에서 부스를 보는 사람의 **왼쪽**. 두 줄이 마주 보므로 부호가 갈린다.
            float sideX = b.center.x + (northRow ? -1f : 1f) * (b.extents.x + SideMargin);
            float frontZ = northRow ? b.min.z - FrontGap : b.max.z + FrontGap;

            var go = new GameObject($"BoothSign_{slot:00}");
            go.transform.SetParent(parent, false);
            go.transform.position = new Vector3(sideX, 0f, frontZ);
            // rotY 180 이면 프리팹의 Front 판이 −z 를 본다(실측). 북쪽 줄은 남쪽(통로)을 봐야 하므로 180.
            go.transform.rotation = Quaternion.Euler(0f, northRow ? 180f : 0f, 0f);
            Loose(go);

            var sign = (GameObject)PrefabUtility.InstantiatePrefab(signPrefab, go.transform);
            sign.name = "Board";
            sign.transform.localPosition = Vector3.zero;
            sign.transform.localRotation = Quaternion.identity;
            sign.transform.localScale = Vector3.one * SignScale;
            foreach (var t in sign.GetComponentsInChildren<Transform>(true)) Loose(t.gameObject);

            // **몸이 걸리는 콜라이더.** 프리팹에는 콜라이더가 없어 사람이 입간판을 그냥 뚫고 지나갔다
            // (2026-09-11 지적). 렌더러 경계를 실측해 상자 하나로 감싼다 — A 자 두 판을 따로 감쌀 만큼
            // 정밀할 필요가 없고, 플레이어 캡슐 반경이 4.8 이라 상자 하나가 오히려 덜 걸린다.
            // 카드·글자 등 나머지 자식은 콜라이더를 지운 채 두어(Box/Slab) 걸리는 것은 이 상자 하나다.
            {
                var bounds = new Bounds();
                bool any = false;
                foreach (var r in sign.GetComponentsInChildren<Renderer>(true))
                {
                    if (!any) { bounds = r.bounds; any = true; }
                    else bounds.Encapsulate(r.bounds);
                }
                if (any)
                {
                    var box = go.AddComponent<BoxCollider>();
                    box.center = go.transform.InverseTransformPoint(bounds.center);
                    box.size = bounds.size;   // 루트 스케일이 1 이라 월드 크기가 곧 로컬 크기다
                }
                else Debug.LogWarning($"[BoothSign] 슬롯 {slot:00} 입간판 렌더러가 없어 콜라이더를 못 만들었다.");
            }

            // 판 두 장의 위치·크기를 **실측해서** 글자를 얹는다 — 프리팹이 바뀌어도 따라간다.
            var front = FindPanel(sign.transform, "Front");
            var back = FindPanel(sign.transform, "Back");

            var label = PanelText(go.transform, "Label", font, front, outward: true, slot, plateMat, chalkMat, photoMat,
                                  out var photoFront, out var numFront, out float labelAspect);
            var labelBack = PanelText(go.transform, "LabelBack", font, back, outward: false, slot, plateMat, chalkMat, photoMat,
                                      out var photoBack, out var numBack, out _);

            // ── 떠 있는 전시 카드 ──────────────────────────────
            // **썸네일이 실제로 로드됐을 때만** 켜진다 (BoothSign.ShowThumbnail).
            // 이름만 있는데 카드를 띄우면 빈 판 12장이 하늘에 뜬다 — 처음 만들 때 그래서 걷어냈다.
            var pivot = new GameObject("CardPivot");
            pivot.transform.SetParent(go.transform, false);
            pivot.transform.position = new Vector3(b.center.x, b.max.y + CardLift, b.center.z);
            pivot.transform.rotation = Quaternion.identity;
            Loose(pivot);

            Box(pivot.transform, "CardRim", Vector3.zero, new Vector3(CardSize + 2.2f, CardSize + 2.2f, 0.9f), cardRimMat);
            var card = Box(pivot.transform, "Card", Vector3.zero, new Vector3(CardSize, CardSize, 1.2f), cardMat);
            pivot.SetActive(false);

            var comp = go.AddComponent<BoothSign>();
            comp.boothId = slot;
            comp.label = label;
            comp.labelBack = labelBack;
            comp.labelBoxAspect = labelAspect;
            comp.photoFaces = new[] { photoFront, photoBack };
            comp.photoPlaceholders = new[] { numFront, numBack };
            comp.cardPivot = pivot.transform;
            comp.cardRenderer = card.GetComponent<Renderer>();

            return go;
        }

        static Renderer FindPanel(Transform signRoot, string keyword)
        {
            foreach (var r in signRoot.GetComponentsInChildren<Renderer>(true))
                if (r.name.Contains(keyword)) return r;
            return null;
        }

        /// <summary>
        /// 입간판 판 위에 놓는 글자. 판이 A 자로 <b>뒤로 기울어 있어서</b> 수직으로 세우면 판을 뚫는다 —
        /// 렌더러 bounds 의 z·y 비로 기울기를 역산해 같은 각도로 눕힌다.
        ///
        /// <para>TMP 3D 글자는 보는 사람이 −z 쪽에 있을 때 바로 읽힌다. 앞판은 부모 로컬 +z 를 보므로
        /// Y 180° 를 한 번 더 준다(이게 없으면 거울 글씨가 된다 — 부스 내부 간판에서 이미 밟은 함정).</para>
        /// </summary>
        static TMP_Text PanelText(Transform parent, string name, TMP_FontAsset font, Renderer panel,
                                  bool outward, int slot, Material plateMat, Material chalkMat, Material photoMat,
                                  out Renderer photoOut, out TMP_Text placeholderOut, out float boxAspectOut)
        {
            photoOut = null;
            placeholderOut = null;
            boxAspectOut = 1.6f;

            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            Loose(go);

            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = font;
            tmp.text = $"{slot}번 부스";
            // 칠판 위의 분필 글씨. 순백은 인쇄물처럼 보여서 살짝 따뜻하게 흐린다.
            tmp.color = new Color(0.92f, 0.93f, 0.88f);
            tmp.alignment = TextAlignmentOptions.Center;
            // **NoWrap 이 맞다.** 줄바꿈은 BoothSign.WrapByWord 가 어절 단위로 미리 넣는다 —
            // TMP 에 맡기면 한글을 글자 단위로 끊어 "스 / 마트팜" 같은 모양이 나온다.
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;

            if (panel == null)
            {
                Debug.LogWarning($"[BoothSign] 슬롯 {slot:00} 표지판에서 '{name}' 판을 못 찾았다 — 글자를 원점에 둔다.");
                return tmp;
            }

            // 판의 로컬 크기·중심 (부모 기준). 프리팹 배율이 바뀌어도 실측이라 따라간다.
            var b = panel.bounds;
            Vector3 localCenter = parent.InverseTransformPoint(b.center);
            float w = b.size.x;
            float lean = Mathf.Atan2(b.size.z, b.size.y) * Mathf.Rad2Deg;   // 수직에서 젖혀진 각도 (실측 ≈ 27°)
            float faceHeight = Mathf.Sqrt(b.size.y * b.size.y + b.size.z * b.size.z);

            float dir = outward ? 1f : -1f;   // 앞판은 로컬 +z, 뒷판은 −z 를 본다
            var normal = new Vector3(0f, Mathf.Sin(lean * Mathf.Deg2Rad), dir * Mathf.Cos(lean * Mathf.Deg2Rad));
            var rot = outward ? Quaternion.Euler(lean, 180f, 0f) : Quaternion.Euler(lean, 0f, 0f);

            float plateW = w * 0.88f;
            float plateH = faceHeight * 0.70f;
            float lift = w * 0.05f;   // 판에서 띄우는 거리 — 배율과 함께 커져야 z-fighting 이 안 난다

            // 나무판 위에 얹는 **칠판**. 세 겹이다 — 짙은 판, 그 위 흰 테두리, 다시 그 위 짙은 면.
            // 가운데를 한 번 더 덮어야 흰 사각형이 **선**으로 남는다(솔리드 박스로 테두리를 만드는 방법).
            // 레퍼런스 사진의 안쪽 흰 선이 이것이다.
            Slab(parent, name + "Plate", localCenter + normal * lift, rot,
                 new Vector3(plateW, plateH, lift * 0.5f), plateMat);
            Slab(parent, name + "Rule", localCenter + normal * (lift * 1.25f), rot,
                 new Vector3(plateW * 0.90f, plateH * 0.90f, lift * 0.3f), chalkMat);
            Slab(parent, name + "RuleInner", localCenter + normal * (lift * 1.45f), rot,
                 new Vector3(plateW * 0.90f - lift * 0.5f, plateH * 0.90f - lift * 0.5f, lift * 0.3f), plateMat);

            // 판 위쪽 = 전시 이미지, 아래쪽 = 이름 (사용자가 올린 카페 입간판 레퍼런스 배치).
            // 위/아래는 판의 기울기를 따라가는 방향이라 rot 를 곱해서 얻는다.
            var faceUp = rot * Vector3.up;
            float photoH = plateH * 0.38f;
            float photoW = plateW * 0.62f;
            float photoY = plateH * 0.22f;

            var photo = Slab(parent, name + "Photo", localCenter + normal * (lift * 1.7f) + faceUp * photoY, rot,
                             new Vector3(photoW, photoH, lift * 0.25f), photoMat);
            photo.SetActive(false);   // 썸네일이 실제로 오면 BoothSign.ShowThumbnail 이 켠다
            photoOut = photo.GetComponent<Renderer>();

            // 이미지가 없는 동안 그 자리에 분필로 쓴 듯 번호를 남긴다 — 위쪽을 비워 두면
            // 아래 이름만 덩그러니 남아 판이 반쪽으로 보인다.
            placeholderOut = ChalkLabel(parent, name + "Num", font, $"{slot:00}",
                                        localCenter + normal * (lift * 1.8f) + faceUp * photoY, rot,
                                        photoW * 0.8f, photoH * 0.7f, new Color(0.72f, 0.75f, 0.70f));

            go.transform.localPosition = localCenter + normal * (lift * 1.8f) - faceUp * (plateH * 0.22f);
            go.transform.localRotation = rot;

            // 좌우 여백. 0.92 는 분필 테두리선(0.90)을 밟았고, 0.70 은 너무 좁혀서 한 줄에 두 글자밖에
            // 안 들어갔다 (2026-09-10 지적). 0.82 면 선 안쪽에 있으면서 네 글자가 들어간다.
            float boxW = plateW * 0.82f;
            // 높이도 넉넉히. 0.32 로 잡았을 때 세 줄이 눌려 글자가 판 폭의 1/3 밖에 못 썼다.
            float boxH = plateH * 0.40f;

            // fontSize 10 = 월드 1 unit (WorldNameplate 실측).
            //
            // **바닥을 낮게 둬야 한다.** 전에 min 을 boxH*1.8 로 잡았더니 "AI 프로젝트 전시관" 에서
            // 자동 축소가 바닥에 걸린 채 멈췄고, 폭 6.89 박스에 11.18 짜리 글자가 그대로 삐져나가
            // 입간판 프레임 뒤로 잘렸다 (2026-09-10). 이름 길이는 60자까지 올 수 있으니
            // (facade.signText 계약) 축소 여지를 넉넉히 준다 — 작아질지언정 잘리지는 않는다.
            tmp.enableAutoSizing = true;
            tmp.fontSizeMax = boxH * 7f;
            tmp.fontSizeMin = boxH * 0.5f;
            tmp.fontSize = tmp.fontSizeMax;
            tmp.rectTransform.sizeDelta = new Vector2(boxW, boxH);
            boxAspectOut = boxW / boxH;   // 줄 수를 고를 때 쓴다 (BoothSign.WrapByWord)
            return tmp;
        }

        /// <summary>기울어진 판 위에 얹는 얇은 판 한 장. 칠판·테두리를 같은 방식으로 만든다.</summary>
        static GameObject Slab(Transform parent, string name, Vector3 localPos, Quaternion rot, Vector3 size, Material mat)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = rot;
            go.transform.localScale = size;
            Object.DestroyImmediate(go.GetComponent<Collider>());
            go.GetComponent<Renderer>().sharedMaterial = mat;
            Loose(go);
            return go;
        }

        /// <summary>
        /// 판 위에 얹는 짧은 분필 글자 (전시 이미지가 없을 때의 번호). 자동 축소 없이 고정 크기다 —
        /// 두 글자짜리라 넘칠 일이 없고, 칸마다 크기가 달라지면 오히려 지저분하다.
        /// </summary>
        static TMP_Text ChalkLabel(Transform parent, string name, TMP_FontAsset font, string text,
                                   Vector3 localPos, Quaternion rot, float boxW, float boxH, Color color)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = rot;
            Loose(go);

            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = font;
            tmp.text = text;
            tmp.color = color;
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.fontSize = boxH * 6f;   // fontSize 10 = 월드 1 unit
            tmp.rectTransform.sizeDelta = new Vector2(boxW, boxH);
            return tmp;
        }

        static GameObject Box(Transform parent, string name, Vector3 localPos, Vector3 size, Material mat)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = localPos;
            go.transform.localRotation = Quaternion.identity;
            go.transform.localScale = size;
            Object.DestroyImmediate(go.GetComponent<Collider>());   // 떠 있는 카드에 몸이 걸리면 안 된다
            go.GetComponent<Renderer>().sharedMaterial = mat;
            Loose(go);
            return go;
        }

        /// <summary>정적 플래그를 전부 끈다 — 오클루전 베이크 이후 오브젝트가 통째로 컬링되는 함정(T-253).</summary>
        static void Loose(GameObject go) => GameObjectUtility.SetStaticEditorFlags(go, 0);

        static Material Material(string name, Color baseColor, Color emission, float emissionScale, float smoothness)
        {
            Directory.CreateDirectory(MatDir);
            var path = $"{MatDir}/{name}.mat";
            var mat = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (mat == null)
            {
                // WebGL 은 런타임 셰이더 탐색이 불안정해 URP Lit 을 명시한다 (CLAUDE.md).
                mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
                AssetDatabase.CreateAsset(mat, path);
            }
            mat.SetColor("_BaseColor", baseColor);
            mat.SetFloat("_Smoothness", smoothness);
            if (emissionScale > 0f)
            {
                mat.EnableKeyword("_EMISSION");
                mat.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
                mat.SetColor("_EmissionColor", emission * emissionScale);
            }
            EditorUtility.SetDirty(mat);
            return mat;
        }
    }
}
