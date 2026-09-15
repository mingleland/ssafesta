using System.Collections.Generic;
using Festa.Diagnostics;
using Festa.World.UI;
using TMPro;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.UI;

namespace Festa.World
{
    /// <summary>
    /// <b>Tab</b> 으로 여는 축제장 부스 지도 (사용자 요청 2026-09-10).
    ///
    /// <para><b>배경은 축제장을 위에서 찍은 실제 그림</b>이다 — <c>FestaMinimapBaker</c> 가 직교
    /// 카메라로 한 번 구워 둔 텍스처다. 그 위에 부스 12칸의 전시 이미지와 프로젝트명을 카드로
    /// 얹고, 내 위치를 호박색 점으로 계속 찍는다. 임대되지 않은 칸은 "부스 없음" 이다.</para>
    ///
    /// <para><b>세로다.</b> 사람은 동쪽 복도에서 들어와 서쪽으로 걸어 들어간다. 가로로 그리면
    /// 걷는 방향이 화면 오른쪽→왼쪽이 되어 눈과 발이 어긋난다. 월드 x 가 화면 세로축이고
    /// <b>입구가 아래, 안쪽이 위</b>다 — 걸어 들어가면 점이 올라간다. 굽는 카메라도 같은 규약이다.</para>
    ///
    /// <para><b>여기까지 세 번 갈아엎었다</b> (2026-09-10). ① 밝은 카드 격자 → 지도가 아니라 표였다.
    /// ② 어두운 네온 + 오른쪽 목록 → 같은 이름이 두 번 나오고 화면만 잡아먹었다. ③ 지금은
    /// <b>진짜 탑뷰 사진 위에 카드를 얹는다</b> — 사용자 요청 "실제 게임 미니맵 하듯이".</para>
    ///
    /// <para>카드 크기는 사진 비율에서 역산했다. 배경을 늘리면 파라솔이 타원이 되므로 비율은
    /// 고정이고, 그 폭 안에서 두 줄이 겹치지 않는 최대치가 카드 폭이다.</para>
    ///
    /// <para><b>복도에서부터 열린다.</b> 축제장 안에서만 열면 "가는 길에 어디로 갈지" 를 못 본다.
    /// 11층 방(z &lt; -118)과 내부 부스 홀(x &gt; 500)만 뺀다. 복도에 있는 동안에는 <b>내 위치 점을
    /// 아예 끈다</b> — 지도 밖에 있는데 가장자리에 붙여 두면 거기 있는 것처럼 읽힌다.</para>
    ///
    /// <para><b>이미지·이름은 다시 묻지 않는다.</b> <see cref="BoothSignPresenter"/> 가 표지판을
    /// 채우며 받아 둔 것을 읽는다 — 일괄 조회 endpoint 가 없어 12칸 × 2요청이 이미 부담이다(#171 ④).</para>
    ///
    /// <para><b>Tab 은 WebGL 에서 위험할 수 있다.</b> 브라우저가 포커스 이동에 쓰는 키라 FE 임베드
    /// 안에서는 캔버스 밖으로 포커스가 빠질 수 있다. 대체키 <see cref="AltKey"/>(M)도 같이 받는다.</para>
    ///
    /// 로컬 표시 전용 — NetworkObject 없음.
    /// </summary>
    public class FestivalMapOverlay : MonoBehaviour
    {
        public const float FestivalMaxX = -228f;   // 축제 부지 경계 (WorldBgm 과 같은 값)
        public const float RoomMaxZ = -118f;       // 11층 방 경계
        public const float InteriorMinX = 500f;    // 내부 부스 홀 경계

        /// <summary>Tab 이 브라우저에 먹힐 때를 위한 대체 키.</summary>
        public const KeyCode AltKey = KeyCode.M;

        public static bool Enabled = true;
        public static bool IsOpen { get; private set; }

        static FestivalMapOverlay _instance;

        // ── 색 ────────────────────────────────────────────────────
        // 네온은 남기되 **선과 점에만** 쓴다. 면을 시안으로 채웠더니 촌스러웠다 (2026-09-10 지적).
        static readonly Color Veil     = new(0.016f, 0.020f, 0.027f, 0.92f);  // 뒤 월드 암막 — 진하게 덮어야 지도가 뜬다
        static readonly Color Panel    = new(0.086f, 0.098f, 0.122f, 1f);     // 패널 바탕
        static readonly Color Surface  = new(0.153f, 0.169f, 0.204f, 1f);     // 카드 바탕 — 배경 사진 위에서 떠 보여야 한다
        static readonly Color Hairline = new(1f, 1f, 1f, 0.22f);              // 1 px 실선
        static readonly Color Teal     = new(0.514f, 0.918f, 0.882f, 1f);     // 강조 — 아껴 쓴다
        static readonly Color Ivory    = new(0.980f, 0.988f, 1f, 1f);
        static readonly Color Muted    = new(0.639f, 0.678f, 0.741f, 1f);
        static readonly Color VacantBg = new(0.176f, 0.192f, 0.224f, 1f);
        static readonly Color Amber    = new(0.941f, 0.706f, 0.161f, 1f);

        // ── 치수 (기준 해상도 1920×1080) ──────────────────────────
        // 배경이 **실제 탑뷰 사진**이라 지도의 가로:세로는 사진 비율(370:640 ≈ 0.578)에 묶인다.
        // 늘리면 원형 파라솔이 타원이 되므로 비율은 건드리지 않는다.
        //
        // 카드는 **실제 위치 그대로** 놓는다 — 사진 속 부스 지붕 위에 얹혀야 하므로 안쪽으로
        // 당기면(inset) 그만큼 어긋난다. 한 번 그렇게 만들었다가 36 px 씩 밀려 있었다.
        // 대신 굽는 범위를 690 으로 넓혀(FestivalMinimapArea) 양 끝 줄도 그림 안에 들어오게 했다 —
        // 640 일 때는 카드가 그림 테두리를 11~18 px 밟았다.
        //
        // 크기는 한 번 키웠다 (2026-09-10 — "오버레이도 더 크게"). MapH 900 → MapW 482,
        // 두 줄 간격 222 → 카드 폭 158 이면 사이가 64 px 남는다.
        const float PanelW = 700f, PanelH = 1046f;
        const float MapH = 900f;
        // 카드 높이 134 는 줄 간격(150 px)에서 역산한 상한이다 — 그보다 크면 위아래 카드가 닿는다.
        // 자막을 70 으로 키운 것은 이름을 **두 줄**로 접어 크게 쓰기 위해서다(2026-09-11 "가독성").
        const float CardW = 158f, CardH = 134f;
        const float CaptionH = 70f;
        const float CardPad = 12f;   // 카드 안쪽 좌우 여백 — 글자가 테두리에 붙지 않게

        float MapW => MapH * FestivalMinimapArea.Aspect;

        Canvas _canvas;
        RectTransform _mapArea;
        RectTransform _dot;
        Image _dotCore;
        readonly Dictionary<int, Cell> _cells = new();
        readonly Dictionary<int, Bounds> _slotBounds = new();
        bool _built;

        sealed class Cell
        {
            public Image Edge;
            public GameObject Content;   // 사진 + 자막 + 번호
            public GameObject Vacant;    // "부스 없음"
            public RawImage Photo;
            public TMP_Text Name;
            public bool LastRented;
            public Texture LastTexture;
            public string LastName;
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Enabled || _instance != null) return;
            var go = new GameObject("@FestivalMapOverlay");
            DontDestroyOnLoad(go);
            _instance = go.AddComponent<FestivalMapOverlay>();
        }

        void Start()
        {
            DiagnosticKeys.ClaimExternal("FestivalMapOverlay 지도(신규 IS tabKey)", KeyCode.Tab);
            DiagnosticKeys.ClaimExternal("FestivalMapOverlay 지도 대체키(신규 IS mKey)", AltKey);
        }

        void Update()
        {
            if (!_built && !TryBuild()) return;

            bool known = TryGetPlayer(out var player);
            bool allowed = known && Available(player);
            var kb = Keyboard.current;
            // 지도는 토글하지 않는다. Tab을 잠깐 눌렀다가 놓은 뒤에도 화면과 입력 잠금이
            // 남는 것은 일반적인 게임 미니맵 조작과 어긋난다. 브라우저가 Tab을 가져가는
            // 환경의 대체키 M도 같은 hold 규칙을 써서 입력 방식이 둘로 갈리지 않게 한다.
            bool held = kb != null && (kb.tabKey.isPressed || kb.mKey.isPressed);
            bool shouldOpen = held && allowed && !Blocked();
            if (shouldOpen && !IsOpen) Open();
            else if (!shouldOpen && IsOpen) Close();

            if (IsOpen) { RefreshCells(); PlaceDot(player); }
        }

        /// <summary>지도를 열 수 있는 구간인가. <b>복도에서부터</b> 열린다.</summary>
        static bool Available(Vector3 p)
        {
            if (p.x < FestivalMaxX) return true;
            if (p.x > InteriorMinX) return false;
            return p.z >= RoomMaxZ;
        }

        static bool Blocked()
            => Festa.Integration.InputBridge.IsLocked
            || InteractionFocusCamera.IsFocused
            || Festa.Content.BoothInteractionInput.HasInteractTarget;

        static bool TryGetPlayer(out Vector3 p)
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var obj = (nm != null && nm.IsClient) ? nm.LocalClient?.PlayerObject : null;
            if (obj != null) { p = obj.transform.position; return true; }
            if (Application.isEditor && Camera.main != null) { p = Camera.main.transform.position; return true; }
            p = default;
            return false;
        }

        void Open() { IsOpen = true; _canvas.gameObject.SetActive(true); }
        void Close() { IsOpen = false; if (_canvas != null) _canvas.gameObject.SetActive(false); }

        // ── 만들기 ────────────────────────────────────────────────

        bool TryBuild()
        {
            var festivalRoot = GameObject.Find("@Festival");
            if (festivalRoot == null) return false;
            var slots = festivalRoot.transform.Find("Festival_Slots");
            if (slots == null) return false;

            _slotBounds.Clear();
            for (int i = 1; i <= BoothSignPresenter.SlotCount; i++)
            {
                var t = slots.Find($"FestivalSlot_{i:00}");
                var combined = t != null ? t.Find($"FestivalSlot_{i:00}_Combined") : null;
                var r = combined != null ? combined.GetComponent<Renderer>() : null;
                if (r != null) _slotBounds[i] = r.bounds;
            }
            if (_slotBounds.Count == 0) return false;

            BuildUi();
            _built = true;
            return true;
        }

        void BuildUi()
        {
            _canvas = FestaUiKit.OverlayCanvas(transform, "FestivalMapCanvas", 480);

            var dim = FestaUiKit.Backdrop(_canvas.transform);
            dim.color = Veil;

            var panel = FestaUiKit.Rect(_canvas.transform, "Panel", Panel, 18);
            FestaUiKit.Place(panel.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), Vector2.zero,
                             new Vector2(PanelW, PanelH));
            var root = panel.rectTransform;

            BuildHeader(root);

            // 배경 = 축제장을 위에서 직교로 찍어 구운 사진 (FestaMinimapBaker). 실시간 카메라가
            // 아니라 텍스처 한 장이다 — 축제장은 정적이라 매 프레임 다시 그릴 이유가 없다.
            var mapGo = new GameObject("MapArea", typeof(RectTransform), typeof(RawImage));
            mapGo.transform.SetParent(root, false);
            _mapArea = (RectTransform)mapGo.transform;
            FestaUiKit.Place(_mapArea, new Vector2(0.5f, 0f), new Vector2(0.5f, 0f),
                             new Vector2(0f, 22f), new Vector2(MapW, MapH));

            var shot = Resources.Load<Texture2D>(FestivalMinimapArea.ResourcePath);
            var raw = mapGo.GetComponent<RawImage>();
            raw.raycastTarget = false;
            if (shot != null)
            {
                raw.texture = shot;
                raw.color = Color.white;   // 굽는 쪽에서 이미 밝기를 올려 뒀다 — 여기서 더 눕히면 다시 어두워진다
            }
            else
            {
                // 조용히 빈 화면을 내지 않는다 — 굽는 것을 잊었다는 사실이 드러나야 한다 (T-24 원칙).
                raw.color = new Color(0.08f, 0.09f, 0.11f, 1f);
                Debug.LogWarning($"[FestivalMapOverlay] Resources/{FestivalMinimapArea.ResourcePath} 가 없다 — " +
                                 "메뉴 'Festa/World/축제장 미니맵 이미지 굽기 (탑뷰)' 를 한 번 돌려야 한다.");
            }

            foreach (var kv in _slotBounds) BuildCell(kv.Key, kv.Value);
            BuildDot();

            _canvas.gameObject.SetActive(false);
        }

        void BuildHeader(RectTransform root)
        {
            FestaUiKit.Label(root, "F E S T I V A L", 15f, new Vector2(44f, -32f), new Vector2(360f, 22f),
                             Teal, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));

            var title = FestaUiKit.Label(root, "축제장 부스", 32f, new Vector2(42f, -54f), new Vector2(420f, 46f),
                                         Ivory, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));
            FestaUiKit.Display(title);

            FestaUiKit.Label(root, "입구 아래 · 안쪽 위", 16f, new Vector2(-44f, -36f), new Vector2(300f, 22f),
                             Muted, FontStyles.Normal, TextAlignmentOptions.Right, new Vector2(1f, 1f));
            FestaUiKit.Label(root, "Tab · Esc 닫기", 16f, new Vector2(-44f, -60f), new Vector2(300f, 22f),
                             Muted, FontStyles.Normal, TextAlignmentOptions.Right, new Vector2(1f, 1f));

            var rule = FestaUiKit.Rect(root, "Rule", Hairline, 1);
            FestaUiKit.Place(rule.rectTransform, new Vector2(0.5f, 1f), new Vector2(0.5f, 1f),
                             new Vector2(0f, -104f), new Vector2(PanelW - 88f, 1f));
        }

        void BuildCell(int slot, Bounds world)
        {
            // 바깥 실선 한 겹 → 안에 카드. 테두리를 별도 사각형으로 깔면 1 px 선이 또렷하다.
            var edge = FestaUiKit.Rect(_mapArea, $"Cell_{slot:00}", Hairline, 10);
            var rt = edge.rectTransform;
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = new Vector2(CardW, CardH);
            rt.anchoredPosition = WorldToMap(world.center);   // **실제 위치 그대로** — 배경 사진 위 부스에 얹혀야 한다

            var surface = FestaUiKit.Rect(edge.transform, "Surface", Surface, 10);
            FestaUiKit.Stretch(surface.rectTransform);
            surface.rectTransform.offsetMin = new Vector2(1f, 1f);
            surface.rectTransform.offsetMax = new Vector2(-1f, -1f);

            // ── 임대된 칸 ──
            var content = new GameObject("Content", typeof(RectTransform));
            content.transform.SetParent(surface.transform, false);
            var crt = (RectTransform)content.transform;
            FestaUiKit.Stretch(crt);

            // 사진은 카드 **위쪽**, 이름은 아래 자막. 사진 위에 글자를 겹치면 밝은 썸네일에서 묻힌다.
            var photoGo = new GameObject("Photo", typeof(RectTransform), typeof(RawImage));
            photoGo.transform.SetParent(crt, false);
            var prt = (RectTransform)photoGo.transform;
            prt.anchorMin = new Vector2(0f, 0f);
            prt.anchorMax = new Vector2(1f, 1f);
            prt.offsetMin = new Vector2(0f, CaptionH);
            prt.offsetMax = Vector2.zero;
            var raw = photoGo.GetComponent<RawImage>();
            raw.color = new Color(1f, 1f, 1f, 0.06f);   // 그림이 오기 전에는 거의 빈 면
            raw.raycastTarget = false;

            var caption = FestaUiKit.Rect(crt, "Caption", new Color(0.055f, 0.063f, 0.078f, 0.98f), 9);
            FestaUiKit.Place(caption.rectTransform, new Vector2(0.5f, 0f), new Vector2(0.5f, 0f),
                             Vector2.zero, new Vector2(CardW - 2f, CaptionH));

            var name = FestaUiKit.Label(caption.rectTransform, $"{slot}번 부스", 22f, new Vector2(0f, 0f),
                                        new Vector2(CardW - CardPad * 2f, CaptionH - 8f), Ivory, FontStyles.Bold,
                                        TextAlignmentOptions.Center, new Vector2(0.5f, 0.5f),
                                        new Vector2(0.5f, 0.5f));
            // **디스플레이 글꼴(주아)로 바꾼다.** 본문 글꼴은 SDF 샘플링이 28 이라 이 크기에서
            // 흐리게 번진다 — 주아는 90 으로 구워져 있어 같은 크기에서 훨씬 또렷하다
            // (2026-09-10 "글씨가 너무 흐리다").
            FestaUiKit.Display(name);
            name.enableAutoSizing = true;
            name.fontSizeMin = 17f;
            name.fontSizeMax = 26f;
            // **NoWrap.** TMP 에 맡기면 한글을 글자 단위로 끊어 "재활용 분류 카 / 메라" 가 됐다
            // (2026-09-11 실측). 줄바꿈은 RefreshCells 가 BoothSign.WrapByWord 로 어절 단위로 미리 넣는다 —
            // 입간판과 같은 규칙, 같은 코드다.
            name.textWrappingMode = TextWrappingModes.NoWrap;
            name.overflowMode = TextOverflowModes.Ellipsis;

            // 번호는 사진 왼쪽 위 모서리에 작게 — 자막 폭을 이름에 다 내주기 위해서다.
            var badge = FestaUiKit.Rect(crt, "Badge", new Color(0.043f, 0.051f, 0.063f, 0.92f), 8);
            FestaUiKit.Place(badge.rectTransform, new Vector2(0f, 1f), new Vector2(0f, 1f),
                             new Vector2(7f, -7f), new Vector2(38f, 28f));
            var num = FestaUiKit.Label(badge.rectTransform, slot.ToString("00"), 17f, Vector2.zero,
                                       Vector2.zero, Teal, FontStyles.Bold, TextAlignmentOptions.Center);
            FestaUiKit.Stretch(num.rectTransform);

            // ── 빈 칸 ──
            // 반투명 덮개로 가리면 아래 글자가 비쳐 이름이 두 겹으로 보인다 (2026-09-10 실측).
            // 아예 **다른 층을 켜고 끈다**.
            var vacant = FestaUiKit.Rect(surface.transform, "Vacant", VacantBg, 10);
            FestaUiKit.Stretch(vacant.rectTransform);
            FestaUiKit.Label(vacant.rectTransform, $"{slot:00}", 17f, new Vector2(0f, -10f),
                             new Vector2(CardW - CardPad * 2f, 20f), new Color(0.56f, 0.60f, 0.67f, 1f),
                             FontStyles.Bold, TextAlignmentOptions.Center, new Vector2(0.5f, 1f));
            FestaUiKit.Label(vacant.rectTransform, "부스 없음", 20f, Vector2.zero, new Vector2(CardW - CardPad * 2f, 24f),
                             Muted, FontStyles.Normal, TextAlignmentOptions.Center,
                             new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f));
            vacant.gameObject.SetActive(false);

            _cells[slot] = new Cell
            {
                Edge = edge, Content = content, Vacant = vacant.gameObject, Photo = raw, Name = name,
                LastRented = true, LastName = null, LastTexture = null,
            };
        }

        void BuildDot()
        {
            // 바깥 글로우, 그 **자식**이 알맹이다. 자식이 나중에 그려지므로 이 순서라야 위로 온다.
            // 칸보다 뒤에 만들어야 카드 위에 올라온다.
            var glow = FestaUiKit.Rect(_mapArea, "Player", new Color(0.941f, 0.706f, 0.161f, 0.22f), 12);
            _dot = glow.rectTransform;
            _dot.anchorMin = _dot.anchorMax = new Vector2(0.5f, 0.5f);
            _dot.pivot = new Vector2(0.5f, 0.5f);
            _dot.sizeDelta = new Vector2(26f, 26f);

            var core = FestaUiKit.Rect(_dot, "Core", Amber, 6);
            core.rectTransform.anchorMin = Vector2.zero;
            core.rectTransform.anchorMax = Vector2.one;
            core.rectTransform.offsetMin = new Vector2(8.5f, 8.5f);
            core.rectTransform.offsetMax = new Vector2(-8.5f, -8.5f);
            _dotCore = core;
        }

        // ── 갱신 ──────────────────────────────────────────────────

        void RefreshCells()
        {
            foreach (var kv in _cells)
            {
                int slot = kv.Key;
                var cell = kv.Value;

                bool rented = BoothVacancyPresenter.IsRented(slot);
                if (cell.LastRented != rented)
                {
                    cell.Content.SetActive(rented);
                    cell.Vacant.SetActive(!rented);
                    cell.Edge.color = rented ? Hairline : new Color(1f, 1f, 1f, 0.04f);
                    cell.LastRented = rented;
                }
                if (!rented) continue;

                if (!BoothSignPresenter.TryGetInfo(slot, out var info)) continue;

                if (cell.LastName != info.Name)
                {
                    // 어절 단위 두 줄 접기. 자막 상자 비율(가로/세로)을 넘겨 줄 수를 고르게 한다.
                    float aspect = (CardW - CardPad * 2f) / (CaptionH - 8f);
                    cell.Name.text = BoothSign.WrapByWord(info.Name, 2, aspect);
                    cell.LastName = info.Name;
                }
                if (cell.LastTexture != info.Thumbnail && info.Thumbnail != null)
                {
                    cell.Photo.texture = info.Thumbnail;
                    cell.Photo.color = Color.white;
                    cell.LastTexture = info.Thumbnail;
                }
            }
        }

        void PlaceDot(Vector3 world)
        {
            if (_dot == null) return;

            // **복도에서는 점을 아예 끈다.** 전에는 가장자리에 붙이고 흐리게 뒀는데, 지도 안에
            // 있지도 않은 점이 테두리에 걸쳐 있어 "저기 있는 건가" 로 읽혔다 (2026-09-10 지적).
            // 축제장 밖이면 내 위치는 지도가 말할 수 있는 것이 아니다 — 안 그리는 것이 정직하다.
            bool inside = world.x < FestivalMaxX;
            if (_dot.gameObject.activeSelf != inside) _dot.gameObject.SetActive(inside);
            if (!inside) return;

            _dot.anchoredPosition = WorldToMap(world);   // 점은 사진과 1:1 — inset 없이
        }

        /// <summary>
        /// 월드 좌표 → 지도 좌표. 배경 사진과 <b>같은 규약</b>(<see cref="FestivalMinimapArea"/>)을 쓴다 —
        /// 그래야 사진 속 부스 지붕 위에 카드가 정확히 얹힌다.
        ///
        /// <para><paramref name="inset"/> 는 카드처럼 <b>크기가 있는 것</b>을 놓을 때 준다. 카드는
        /// 중심을 기준으로 놓이므로, 가장자리 부스의 카드가 지도 밖으로 반쯤 나가지 않게 그만큼
        /// 안으로 좁혀 환산한다. 내 위치 점은 사진과 1:1 로 맞아야 하므로 inset 없이 쓴다.</para>
        /// </summary>
        Vector2 WorldToMap(Vector3 world, Vector2 inset = default)
        {
            var n = FestivalMinimapArea.Normalized(world);
            return new Vector2((n.x - 0.5f) * (MapW - inset.x),
                               (n.y - 0.5f) * (MapH - inset.y));
        }
    }
}
