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
    /// 축제 구역에서 <b>Tab</b> 을 누르면 뜨는 축제장 지도 (사용자 요청 2026-09-10).
    ///
    /// <para>부스 12칸을 칸으로 나눠 프로젝트명과 전시 이미지를 보여 주고, 내 위치를 노란 점으로
    /// 계속 찍는다. 임대되지 않은 칸은 회색 "부스 없음" 이다.</para>
    ///
    /// <para><b>축제 구역에서만 뜬다.</b> 경계는 <see cref="WorldBgm"/> 의 구역 판정과 같은
    /// <c>x &lt; -228</c> 을 쓴다 — 두 곳이 갈리면 "음악은 축제인데 지도는 안 열리는" 구간이 생긴다.</para>
    ///
    /// <para><b>칸은 실제 크기가 아니다.</b> 축제장은 가로 710 인데 작은 수레는 폭 18 이라,
    /// 축척 그대로 그리면 칸이 25 px 도 안 돼 이름은커녕 그림도 못 넣는다. 그래서 <b>위치는 실측
    /// 그대로 두고 칸만 키웠다</b> — 부스 간격이 약 115 라 칸 폭 150 이 겹치지 않고 딱 들어맞는다.
    /// 노란 점은 실제 좌표를 그대로 환산하므로 칸과의 상대 위치가 맞는다.</para>
    ///
    /// <para><b>이미지·이름은 다시 묻지 않는다.</b> <see cref="BoothSignPresenter"/> 가 표지판을
    /// 채우며 받아 둔 것을 그대로 읽는다 — 일괄 조회 endpoint 가 없어 12칸 × 2요청이 이미
    /// 부담인데(#171 ④) 지도가 또 물으면 두 배가 된다.</para>
    ///
    /// <para><b>Tab 은 WebGL 에서 위험할 수 있다.</b> 브라우저가 포커스 이동에 쓰는 키라,
    /// FE 임베드(iframe) 안에서는 캔버스 밖으로 포커스가 빠질 수 있다. 그래서 <see cref="AltKey"/>
    /// (M) 도 같이 받는다 — 실 임베드에서 Tab 이 먹히는지는 빌드 검증 항목이다.</para>
    ///
    /// 로컬 표시 전용 — NetworkObject 없음.
    /// </summary>
    public class FestivalMapOverlay : MonoBehaviour
    {
        /// <summary>축제 구역 경계 (world x). WorldBgm.ZoneWeights 와 같은 값이어야 한다.</summary>
        public const float FestivalMaxX = -228f;

        /// <summary>Tab 이 브라우저에 먹힐 때를 위한 대체 키.</summary>
        public const KeyCode AltKey = KeyCode.M;

        public static bool Enabled = true;
        public static bool IsOpen { get; private set; }

        static FestivalMapOverlay _instance;

        // 칸 크기·간격 (캔버스 단위, 기준 해상도 1920×1080).
        //
        // **지도 크기는 실측에서 역산했다.** 부스 x 간격이 약 115~120 world 라 지도 폭이 좁으면
        // 칸이 겹친다 — 처음 잡은 960 에서는 가로 15 px, 세로 50 px 씩 겹쳤다.
        // 가로 1320 이면 칸 사이 45 px, 세로 660 이면 두 줄 사이 37 px 이 남는다.
        const float PanelW = 1400f, PanelH = 800f;
        const float MapW = 1320f, MapH = 660f;
        const float CellW = 148f, CellH = 176f;

        Canvas _canvas;
        RectTransform _mapArea;
        RectTransform _dot;
        readonly Dictionary<int, Cell> _cells = new();
        readonly Dictionary<int, Bounds> _slotBounds = new();
        Bounds _festival;
        bool _built;

        sealed class Cell
        {
            public Image Frame;
            public RawImage Photo;
            public TMP_Text Name;
            public Image Shade;
            public TMP_Text Empty;
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

            bool inFestival = TryGetPlayer(out var player) && player.x < FestivalMaxX;

            // 구역을 벗어나면 열려 있어도 닫는다 — 11층 복도에서 축제장 지도가 떠 있으면 거짓말이다.
            if (IsOpen && !inFestival) Close();

            var kb = Keyboard.current;
            if (kb != null && inFestival && !Blocked())
            {
                if (kb.tabKey.wasPressedThisFrame || kb.mKey.wasPressedThisFrame)
                {
                    if (IsOpen) Close(); else Open();
                }
            }
            if (IsOpen && kb != null && kb.escapeKey.wasPressedThisFrame) Close();

            if (IsOpen)
            {
                RefreshCells();
                PlaceDot(player);
            }
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

        void Open()
        {
            IsOpen = true;
            _canvas.gameObject.SetActive(true);
        }

        void Close()
        {
            IsOpen = false;
            if (_canvas != null) _canvas.gameObject.SetActive(false);
        }

        // ── 만들기 ────────────────────────────────────────────────

        /// <summary>
        /// 축제장 실물 좌표를 읽어 지도를 한 번만 짓는다. 씬이 아직 안 서 있으면 다음 프레임에 다시 본다.
        /// </summary>
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
            FestaUiKit.Backdrop(_canvas.transform);

            var panel = FestaUiKit.Panel(_canvas.transform, "Panel");
            FestaUiKit.Place(panel.rectTransform, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), Vector2.zero,
                             new Vector2(PanelW, PanelH));

            FestaUiKit.TitleBanner(panel.rectTransform, "축제장 지도", new Vector2(0f, -34f), new Vector2(260f, 46f),
                                   24f, new Vector2(0.5f, 1f));
            FestaUiKit.Label(panel.rectTransform, "Tab · Esc 로 닫기", 16f, new Vector2(0f, -PanelH + 40f),
                             new Vector2(360f, 26f), FestaUiKit.Muted, anchor: new Vector2(0.5f, 1f));

            var area = FestaUiKit.Rect(panel.transform, "MapArea", new Color(0.10f, 0.11f, 0.15f, 0.55f), 18);
            _mapArea = area.rectTransform;
            FestaUiKit.Place(_mapArea, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(0f, -8f),
                             new Vector2(MapW, MapH));

            foreach (var kv in _slotBounds) BuildCell(kv.Key, kv.Value);

            // 내 위치. **맨 마지막**에 만든다 — 형제 순서가 곧 그리는 순서라 칸 위에 올라와야 한다.
            //
            // 바깥이 어두운 테두리, 그 **자식**이 노란 점이다. 자식은 부모보다 나중에 그려지므로
            // 이 순서라야 노란색이 위로 온다 — 반대로 짜면 테두리가 점을 덮는다.
            var ring = FestaUiKit.Rect(_mapArea, "Player", new Color(0.12f, 0.10f, 0.02f, 0.9f), 11);
            _dot = ring.rectTransform;
            _dot.anchorMin = _dot.anchorMax = new Vector2(0.5f, 0.5f);
            _dot.pivot = new Vector2(0.5f, 0.5f);
            _dot.sizeDelta = new Vector2(22f, 22f);

            var core = FestaUiKit.Rect(_dot, "Core", new Color(1f, 0.85f, 0.15f, 1f), 8);
            core.rectTransform.anchorMin = Vector2.zero;
            core.rectTransform.anchorMax = Vector2.one;
            core.rectTransform.offsetMin = new Vector2(3f, 3f);
            core.rectTransform.offsetMax = new Vector2(-3f, -3f);

            _canvas.gameObject.SetActive(false);
        }

        void BuildCell(int slot, Bounds world)
        {
            var frame = FestaUiKit.Rect(_mapArea, $"Cell_{slot:00}", FestaUiKit.Paper, 14);
            var rt = frame.rectTransform;
            rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
            rt.pivot = new Vector2(0.5f, 0.5f);
            rt.sizeDelta = new Vector2(CellW, CellH);
            rt.anchoredPosition = WorldToMap(world.center);

            var photo = new GameObject("Photo", typeof(RectTransform), typeof(RawImage));
            photo.transform.SetParent(rt, false);
            var prt = (RectTransform)photo.transform;
            prt.anchorMin = new Vector2(0f, 0f);
            prt.anchorMax = new Vector2(1f, 1f);
            prt.offsetMin = new Vector2(8f, 40f);
            prt.offsetMax = new Vector2(-8f, -8f);
            var raw = photo.GetComponent<RawImage>();
            raw.color = new Color(1f, 1f, 1f, 0.14f);   // 그림이 오기 전에는 빈 자리 표시

            var name = FestaUiKit.Label(rt, $"{slot}번 부스", 15f, new Vector2(0f, 20f),
                                        new Vector2(CellW - 12f, 32f), FestaUiKit.Text,
                                        anchor: new Vector2(0.5f, 0f));
            name.alignment = TextAlignmentOptions.Center;
            name.enableAutoSizing = true;
            name.fontSizeMin = 9f;
            name.fontSizeMax = 15f;
            name.textWrappingMode = TextWrappingModes.Normal;
            name.overflowMode = TextOverflowModes.Ellipsis;

            // 빈 부스용 회색 덮개. 칸을 지우지 않고 덮는 이유는 **자리 자체가 정보**이기 때문이다 —
            // 몇 번 자리가 비었는지 보여야 한다.
            var shade = FestaUiKit.Rect(rt, "Shade", new Color(0.44f, 0.45f, 0.50f, 0.92f), 14);
            FestaUiKit.Stretch(shade.rectTransform);
            var empty = FestaUiKit.Label(shade.rectTransform, "부스 없음", 15f, Vector2.zero,
                                         new Vector2(CellW - 12f, 40f), new Color(1f, 1f, 1f, 0.85f));
            empty.alignment = TextAlignmentOptions.Center;

            // 덮개는 꺼 둔 채 시작한다. 켜 두고 시작하면 `LastRented=true` 와 값이 같아 갱신이
            // 아무것도 안 하고, 임대된 부스까지 회색으로 남는다.
            shade.gameObject.SetActive(false);

            _cells[slot] = new Cell
            {
                Frame = frame, Photo = raw, Name = name, Shade = shade, Empty = empty,
                LastRented = true, LastName = null, LastTexture = null,
            };
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
                    cell.Shade.gameObject.SetActive(!rented);
                    cell.LastRented = rented;
                }
                if (!rented) continue;

                if (!BoothSignPresenter.TryGetInfo(slot, out var info)) continue;

                if (cell.LastName != info.Name)
                {
                    cell.Name.text = info.Name;
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
            _dot.anchoredPosition = WorldToMap(world);
        }

        /// <summary>
        /// 월드 좌표 → 지도 좌표. <b>북쪽 위·동쪽 오른쪽</b>의 평범한 방향이다(월드 +z 가 위, +x 가 오른쪽).
        ///
        /// <para>쓸 수 있는 폭·높이에서 칸 한 장을 빼고 환산한다 — 칸은 중심에 놓이므로
        /// 가장자리 부스가 지도 밖으로 반쯤 나가지 않게 하려면 칸 크기만큼 안으로 좁혀야 한다.</para>
        /// </summary>
        Vector2 WorldToMap(Vector3 world)
        {
            float u = Mathf.InverseLerp(_festival.min.x, _festival.max.x, world.x);
            float v = Mathf.InverseLerp(_festival.min.z, _festival.max.z, world.z);
            float usableW = MapW - CellW - 20f;
            float usableH = MapH - CellH - 20f;
            return new Vector2((u - 0.5f) * usableW, (v - 0.5f) * usableH);
        }
    }
}
