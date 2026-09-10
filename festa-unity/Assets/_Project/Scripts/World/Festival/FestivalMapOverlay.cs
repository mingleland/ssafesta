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
    /// <b>Tab</b> 으로 여는 축제장 지도 (사용자 요청 2026-09-10).
    ///
    /// <para>부스 12칸을 칸으로 나눠 프로젝트명·전시 이미지를 보여 주고, 내 위치를 노란 점으로
    /// 계속 찍는다. 임대되지 않은 칸은 회색 "부스 없음" 이다. 오른쪽에 같은 내용을 목록으로 겹쳐
    /// 둔 것은 칸이 작아 긴 이름이 줄어들기 때문이다 — 지도는 위치, 목록은 이름을 맡는다.</para>
    ///
    /// <para><b>세로 지도다.</b> 사람은 동쪽 복도에서 들어와 서쪽으로 걸어 들어간다. 가로로 그리면
    /// 걷는 방향이 화면 오른쪽→왼쪽이 되어 눈과 발이 어긋난다. 그래서 <b>월드 x 를 화면 세로축</b>에,
    /// z 를 가로축에 놓았다 — 입구가 아래, 안쪽이 위다. 걸어 들어가면 점이 위로 올라간다.</para>
    ///
    /// <para><b>칸은 실제 축척이 아니다.</b> 축제장 세로(x)는 710 인데 작은 수레는 폭 18 이라
    /// 축척대로 그리면 칸이 25 px 도 안 된다. 위치는 실측 그대로 두고 칸만 키웠다 — 크기는
    /// 부스 실측 간격에서 역산했다(아래 상수 주석).</para>
    ///
    /// <para><b>복도에서부터 열린다.</b> 축제장 안에서만 열면 "가는 길에 어디로 갈지" 를 못 본다.
    /// 11층 방(z &lt; -118)과 내부 부스 홀(x &gt; 500)만 빼고 열어 준다. 복도에 있는 동안에는
    /// 내 위치가 지도 밖이라 점을 <b>가장자리에 붙이고 흐리게</b> 둔다 — 안에 있는 척하지 않는다.</para>
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
        /// <summary>축제 구역 경계 (world x). WorldBgm.ZoneWeights 와 같은 값이어야 한다.</summary>
        public const float FestivalMaxX = -228f;

        /// <summary>11층 방 경계 (world z). 이보다 남쪽은 방 안이라 지도를 열지 않는다.</summary>
        public const float RoomMaxZ = -118f;

        /// <summary>내부 부스 홀 경계 (world x). 이보다 동쪽은 부스 안이다.</summary>
        public const float InteriorMinX = 500f;

        /// <summary>Tab 이 브라우저에 먹힐 때를 위한 대체 키.</summary>
        public const KeyCode AltKey = KeyCode.M;

        public static bool Enabled = true;
        public static bool IsOpen { get; private set; }

        static FestivalMapOverlay _instance;

        // ── 색 (사용자가 올린 레퍼런스의 어두운 네온 톤) ──────────────
        static readonly Color Ink       = new(0.016f, 0.043f, 0.071f, 0.97f);  // 패널 바탕
        static readonly Color InkDeep   = new(0.008f, 0.027f, 0.047f, 1f);     // 지도 바탕
        static readonly Color Neon      = new(0.208f, 0.894f, 0.941f, 1f);     // 시안 강조
        static readonly Color NeonSoft  = new(0.208f, 0.894f, 0.941f, 0.22f);  // 은은한 테두리·글로우
        static readonly Color Ivory     = new(0.93f, 0.96f, 0.98f, 1f);        // 본문 글자
        static readonly Color Faint     = new(0.56f, 0.64f, 0.71f, 1f);        // 보조 글자
        static readonly Color Vacant    = new(0.24f, 0.27f, 0.32f, 0.95f);     // 빈 부스
        static readonly Color VacantEdge= new(0.38f, 0.42f, 0.48f, 0.8f);
        static readonly Color Amber     = new(1f, 0.79f, 0.24f, 1f);           // 내 위치

        // ── 치수 (기준 해상도 1920×1080) ──────────────────────────
        // 세로 지도라 가로에는 부스 **두 줄**(z 230 / 60), 세로에는 **여섯 칸**(x 간격 약 115)이 온다.
        //   가로: 두 줄 간격 = 0.4595 × (MapW − CellW − 20) ≥ CellW + 20  → MapW 700 이면 CellW ≤ 200
        //   세로: 칸 간격    = 0.162  × (MapH − CellH − 20) ≥ CellH + 14  → MapH 860 이면 CellH ≤ 100
        const float PanelW = 1460f, PanelH = 980f;
        const float MapW = 700f, MapH = 860f;
        const float CellW = 200f, CellH = 100f;
        const float ListW = 660f;

        Canvas _canvas;
        RectTransform _mapArea;
        RectTransform _dot;
        Image _dotCore;
        readonly Dictionary<int, Cell> _cells = new();
        readonly Dictionary<int, Row> _rows = new();
        readonly Dictionary<int, Bounds> _slotBounds = new();
        Bounds _festival;
        bool _built;

        sealed class Cell
        {
            public Image Frame;
            public Image Edge;
            public RawImage Photo;
            public TMP_Text Name;
            public Image Shade;
            public bool LastRented;
            public Texture LastTexture;
            public string LastName;
        }

        sealed class Row
        {
            public TMP_Text Name;
            public TMP_Text Status;
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

            if (IsOpen && !allowed) Close();

            var kb = Keyboard.current;
            if (kb != null && allowed && !Blocked() && (kb.tabKey.wasPressedThisFrame || kb.mKey.wasPressedThisFrame))
            {
                if (IsOpen) Close(); else Open();
            }
            if (IsOpen && kb != null && kb.escapeKey.wasPressedThisFrame) Close();

            if (IsOpen)
            {
                RefreshCells();
                PlaceDot(player);
            }
        }

        /// <summary>
        /// 지도를 열 수 있는 구간인가. <b>복도에서부터</b> 열린다 — 11층 방과 내부 부스 홀만 뺀다.
        /// </summary>
        static bool Available(Vector3 p)
        {
            if (p.x < FestivalMaxX) return true;      // 축제 부지
            if (p.x > InteriorMinX) return false;     // 내부 부스 홀
            return p.z >= RoomMaxZ;                   // 복도·개활 전실
        }

        /// <summary>
        /// 다른 화면이 잡고 있으면 열지 않는다. 슬롯머신에 초점이 가 있는데 Tab 으로 지도가
        /// 겹쳐 뜨면 Esc 하나에 무엇이 닫히는지 알 수 없게 된다.
        /// </summary>
        static bool Blocked()
            => Festa.Integration.InputBridge.IsLocked
            || InteractionFocusCamera.IsFocused
            || Festa.Content.BoothInteractionInput.HasInteractTarget;

        static bool TryGetPlayer(out Vector3 p)
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var obj = (nm != null && nm.IsClient) ? nm.LocalClient?.PlayerObject : null;
            if (obj != null) { p = obj.transform.position; return true; }

            // 에디터 미리보기에서만 카메라로 물러난다 — WorldBgm 과 같은 원칙이다.
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

            // 지도의 좌표계는 **벽 안쪽**이다. 부스만으로 잡으면 통로 여백이 사라져 점이 가장자리에 붙는다.
            _festival = new Bounds(new Vector3(-575f, 0f, 145f), new Vector3(710f, 1f, 370f));

            BuildUi();
            _built = true;
            return true;
        }

        void BuildUi()
        {
            _canvas = FestaUiKit.OverlayCanvas(transform, "FestivalMapCanvas", 480);

            var dim = FestaUiKit.Backdrop(_canvas.transform);
            dim.color = new Color(0f, 0.01f, 0.03f, 0.78f);

            // 패널 — 어두운 바탕에 시안 테두리 한 겹
            var edge = FestaUiKit.Rect(_canvas.transform, "PanelEdge", NeonSoft, 22);
            FestaUiKit.Place(edge.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), Vector2.zero,
                             new Vector2(PanelW + 4f, PanelH + 4f));
            var panel = FestaUiKit.Rect(edge.transform, "Panel", Ink, 20);
            FestaUiKit.Stretch(panel.rectTransform);
            panel.rectTransform.offsetMin = new Vector2(2f, 2f);
            panel.rectTransform.offsetMax = new Vector2(-2f, -2f);
            var root = panel.rectTransform;

            BuildHeader(root);

            // 지도 (왼쪽, 세로)
            var mapEdge = FestaUiKit.Rect(root, "MapEdge", NeonSoft, 16);
            FestaUiKit.Place(mapEdge.rectTransform, new Vector2(0f, 0.5f), new Vector2(0f, 0.5f),
                             new Vector2(36f, -34f), new Vector2(MapW + 4f, MapH + 4f));
            var map = FestaUiKit.Rect(mapEdge.transform, "MapArea", InkDeep, 14);
            FestaUiKit.Stretch(map.rectTransform);
            map.rectTransform.offsetMin = new Vector2(2f, 2f);
            map.rectTransform.offsetMax = new Vector2(-2f, -2f);
            _mapArea = map.rectTransform;

            BuildAisleGuide();
            foreach (var kv in _slotBounds) BuildCell(kv.Key, kv.Value);
            BuildDot();

            BuildList(root);

            _canvas.gameObject.SetActive(false);
        }

        void BuildHeader(RectTransform root)
        {
            var title = FestaUiKit.Label(root, "FESTA", 34f, new Vector2(38f, -30f), new Vector2(200f, 44f),
                                         Ivory, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));
            FestaUiKit.Display(title);
            var accent = FestaUiKit.Label(root, ": 축제장 지도", 34f, new Vector2(160f, -30f), new Vector2(420f, 44f),
                                          Neon, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));
            FestaUiKit.Display(accent);
            FestaUiKit.Label(root, "입구는 아래 · 안쪽은 위 — 걸어 들어가면 점이 올라갑니다", 16f,
                             new Vector2(40f, -78f), new Vector2(760f, 26f), Faint,
                             FontStyles.Normal, TextAlignmentOptions.Left, new Vector2(0f, 1f));

            var rule = FestaUiKit.Rect(root, "Rule", NeonSoft, 2);
            FestaUiKit.Place(rule.rectTransform, new Vector2(0f, 1f), new Vector2(0f, 1f),
                             new Vector2(36f, -104f), new Vector2(PanelW - 72f, 2f));

            FestaUiKit.Label(root, "Tab · Esc 로 닫기", 16f, new Vector2(-40f, -30f), new Vector2(300f, 26f),
                             Faint, FontStyles.Normal, TextAlignmentOptions.Right, new Vector2(1f, 1f));
        }

        /// <summary>가운데 통로를 옅은 선으로 깔아 둔다 — 칸만 있으면 두 줄이 왜 나뉘는지 안 보인다.</summary>
        void BuildAisleGuide()
        {
            var aisle = FestaUiKit.Rect(_mapArea, "Aisle", new Color(0.208f, 0.894f, 0.941f, 0.07f), 8);
            var rt = aisle.rectTransform;
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = new Vector2(64f, MapH - 40f);
            rt.anchoredPosition = new Vector2(WorldToMap(new Vector3(0f, 0f, 145f)).x, 0f);
        }

        void BuildCell(int slot, Bounds world)
        {
            var edge = FestaUiKit.Rect(_mapArea, $"Cell_{slot:00}", Neon, 12);
            var rt = edge.rectTransform;
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = new Vector2(CellW, CellH);
            rt.anchoredPosition = WorldToMap(world.center);

            var frame = FestaUiKit.Rect(edge.transform, "Fill", new Color(0.043f, 0.157f, 0.204f, 0.95f), 11);
            FestaUiKit.Stretch(frame.rectTransform);
            frame.rectTransform.offsetMin = new Vector2(2f, 2f);
            frame.rectTransform.offsetMax = new Vector2(-2f, -2f);

            // 왼쪽 그림 · 오른쪽 번호와 이름 — 칸이 가로로 길어(200×100) 이 배치가 자연스럽다.
            var photoGo = new GameObject("Photo", typeof(RectTransform), typeof(RawImage));
            photoGo.transform.SetParent(frame.transform, false);
            var prt = (RectTransform)photoGo.transform;
            prt.anchorMin = new Vector2(0f, 0f);
            prt.anchorMax = new Vector2(0f, 1f);
            prt.pivot = new Vector2(0f, 0.5f);
            prt.offsetMin = new Vector2(7f, 7f);
            prt.offsetMax = new Vector2(7f + 86f, -7f);
            var raw = photoGo.GetComponent<RawImage>();
            raw.color = new Color(0.208f, 0.894f, 0.941f, 0.12f);
            raw.raycastTarget = false;

            FestaUiKit.Label(frame.rectTransform, slot.ToString("00"), 15f, new Vector2(-10f, -8f),
                             new Vector2(40f, 22f), Neon, FontStyles.Bold, TextAlignmentOptions.Right,
                             new Vector2(1f, 1f));

            var name = FestaUiKit.Label(frame.rectTransform, $"{slot}번 부스", 15f, new Vector2(-10f, 10f),
                                        new Vector2(90f, 54f), Ivory, FontStyles.Normal,
                                        TextAlignmentOptions.Right, new Vector2(1f, 0f));
            name.enableAutoSizing = true;
            name.fontSizeMin = 10f;
            name.fontSizeMax = 15f;
            name.textWrappingMode = TextWrappingModes.Normal;
            name.overflowMode = TextOverflowModes.Ellipsis;

            // 빈 부스 덮개. 칸을 지우지 않고 덮는 이유는 **자리 자체가 정보**라서다 — 몇 번이 비었는지 보여야 한다.
            var shade = FestaUiKit.Rect(edge.transform, "Shade", Vacant, 12);
            FestaUiKit.Stretch(shade.rectTransform);
            var empty = FestaUiKit.Label(shade.rectTransform, $"{slot}번 · 부스 없음", 15f, Vector2.zero,
                                         new Vector2(CellW - 16f, 40f), new Color(0.78f, 0.82f, 0.86f, 0.95f),
                                         FontStyles.Normal, TextAlignmentOptions.Center,
                                         new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f));
            empty.enableAutoSizing = true;
            empty.fontSizeMin = 11f;
            empty.fontSizeMax = 15f;
            shade.gameObject.SetActive(false);   // 켜 둔 채 시작하면 갱신이 값을 같다고 보고 그냥 둔다

            _cells[slot] = new Cell
            {
                Frame = frame, Edge = edge, Photo = raw, Name = name, Shade = shade,
                LastRented = true, LastName = null, LastTexture = null,
            };
        }

        void BuildDot()
        {
            // 바깥이 어두운 테두리, 그 **자식**이 노란 점이다. 자식이 나중에 그려지므로 이 순서라야
            // 노란색이 위로 온다 — 반대로 짜면 테두리가 점을 덮는다. 칸보다 뒤에 만들어 위에 온다.
            var glow = FestaUiKit.Rect(_mapArea, "Player", new Color(1f, 0.79f, 0.24f, 0.28f), 14);
            _dot = glow.rectTransform;
            _dot.anchorMin = _dot.anchorMax = new Vector2(0.5f, 0.5f);
            _dot.pivot = new Vector2(0.5f, 0.5f);
            _dot.sizeDelta = new Vector2(30f, 30f);

            var core = FestaUiKit.Rect(_dot, "Core", Amber, 8);
            core.rectTransform.anchorMin = Vector2.zero;
            core.rectTransform.anchorMax = Vector2.one;
            core.rectTransform.offsetMin = new Vector2(9f, 9f);
            core.rectTransform.offsetMax = new Vector2(-9f, -9f);
            _dotCore = core;
        }

        void BuildList(RectTransform root)
        {
            float x = MapW + 76f;
            FestaUiKit.Label(root, "BOOTH NETWORK", 14f, new Vector2(x, -128f), new Vector2(ListW, 22f),
                             Neon, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));
            var t = FestaUiKit.Label(root, "부스 목록", 26f, new Vector2(x, -154f), new Vector2(ListW, 38f),
                                     Ivory, FontStyles.Bold, TextAlignmentOptions.Left, new Vector2(0f, 1f));
            FestaUiKit.Display(t);

            const float RowH = 54f, Gap = 8f;
            for (int slot = 1; slot <= BoothSignPresenter.SlotCount; slot++)
            {
                float y = -206f - (slot - 1) * (RowH + Gap);
                var row = FestaUiKit.Rect(root, $"Row_{slot:00}", new Color(0.043f, 0.098f, 0.137f, 0.9f), 10);
                FestaUiKit.Place(row.rectTransform, new Vector2(0f, 1f), new Vector2(0f, 1f),
                                 new Vector2(x, y), new Vector2(ListW, RowH));

                var line = FestaUiKit.Rect(row.transform, "Bar", NeonSoft, 2);
                FestaUiKit.Place(line.rectTransform, new Vector2(0f, 0.5f), new Vector2(0f, 0.5f),
                                 new Vector2(0f, 0f), new Vector2(3f, RowH - 14f));

                var name = FestaUiKit.Label(row.rectTransform, $"{slot}번 부스", 18f, new Vector2(20f, 0f),
                                            new Vector2(ListW - 200f, 30f), Ivory, FontStyles.Normal,
                                            TextAlignmentOptions.Left, new Vector2(0f, 0.5f));
                name.enableAutoSizing = true;
                name.fontSizeMin = 12f;
                name.fontSizeMax = 18f;
                name.textWrappingMode = TextWrappingModes.NoWrap;
                name.overflowMode = TextOverflowModes.Ellipsis;

                var status = FestaUiKit.Label(row.rectTransform, "확인 중", 15f, new Vector2(-18f, 0f),
                                              new Vector2(150f, 26f), Neon, FontStyles.Bold,
                                              TextAlignmentOptions.Right, new Vector2(1f, 0.5f));

                _rows[slot] = new Row { Name = name, Status = status };
            }
        }

        // ── 갱신 ──────────────────────────────────────────────────

        void RefreshCells()
        {
            foreach (var kv in _cells)
            {
                int slot = kv.Key;
                var cell = kv.Value;
                var row = _rows.TryGetValue(slot, out var r) ? r : null;

                bool rented = BoothVacancyPresenter.IsRented(slot);
                if (cell.LastRented != rented)
                {
                    cell.Shade.gameObject.SetActive(!rented);
                    cell.Edge.color = rented ? Neon : VacantEdge;
                    if (row != null)
                    {
                        row.Status.text = rented ? "전시 중" : "부스 없음";
                        row.Status.color = rented ? Neon : Faint;
                        if (!rented) { row.Name.text = "—"; row.Name.color = Faint; }
                        else row.Name.color = Ivory;
                    }
                    cell.LastRented = rented;
                }
                if (!rented) continue;

                if (!BoothSignPresenter.TryGetInfo(slot, out var info)) continue;

                if (cell.LastName != info.Name)
                {
                    cell.Name.text = info.Name;
                    if (row != null) row.Name.text = $"{slot}. {info.Name}";
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

            bool inside = world.x < FestivalMaxX;
            var p = WorldToMap(world);

            // 복도에 있는 동안에는 지도 밖이다. 가장자리에 붙이고 흐리게 둔다 — 안에 있는 척하지 않는다.
            float halfW = (MapW - 24f) * 0.5f, halfH = (MapH - 24f) * 0.5f;
            p.x = Mathf.Clamp(p.x, -halfW, halfW);
            p.y = Mathf.Clamp(p.y, -halfH, halfH);
            _dot.anchoredPosition = p;

            var c = _dotCore.color;
            float a = inside ? 1f : 0.45f;
            if (!Mathf.Approximately(c.a, a)) _dotCore.color = new Color(c.r, c.g, c.b, a);
        }

        /// <summary>
        /// 월드 좌표 → 지도 좌표. <b>세로 지도</b>다 — 월드 x 가 화면 세로, z 가 가로다.
        ///
        /// <para>세로: 입구(동쪽, x −220)가 아래, 안쪽(서쪽, x −930)이 위. 걸어 들어가면 점이 올라간다.
        /// 가로: 월드 +z(북쪽 줄)가 오른쪽.</para>
        ///
        /// <para>쓸 수 있는 폭·높이에서 칸 한 장을 빼고 환산한다 — 칸은 중심에 놓이므로 가장자리
        /// 부스가 지도 밖으로 반쯤 나가지 않게 하려면 칸 크기만큼 안으로 좁혀야 한다.</para>
        /// </summary>
        Vector2 WorldToMap(Vector3 world)
        {
            float alongX = Mathf.InverseLerp(_festival.min.x, _festival.max.x, world.x);   // 0 서쪽(안) … 1 동쪽(입구)
            float alongZ = Mathf.InverseLerp(_festival.min.z, _festival.max.z, world.z);
            float usableW = MapW - CellW - 20f;
            float usableH = MapH - CellH - 20f;
            return new Vector2((alongZ - 0.5f) * usableW, (0.5f - alongX) * usableH);
        }
    }
}
