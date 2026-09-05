using System.Collections.Generic;
using TMPro;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Festa.World.UI
{
    /// <summary>
    /// 런타임 uGUI 공용 조립 도우미 — Unity 가 직접 그리는 화면(슬롯머신·타이밍 스톱·프롬프트·로비)의 <b>한 가지 생김새</b>.
    ///
    /// <para>그림은 300Mind "2D Game UI Kit"(<see cref="FestaUiTheme"/>)에서, 글자는 TextMeshPro 맑은고딕 SDF 에서 온다
    /// (WebGL 에서 한글이 깨지지 않는 유일한 경로, T-22). 킷이 밝은 카툰 톤이라 글자는 흰색 + 어두운 외곽선으로 맞춘다.</para>
    ///
    /// <para>사용자 지시(2026-09-06 "UI 킷으로 전부 바꿔라")에 따라 새 화면은 이 함수만 써야 한다. 테마 에셋이 없으면
    /// 코드 생성 둥근 사각형으로 떨어지되 에러 로그를 남긴다 — 조용히 못생겨지지 않는다.</para>
    /// </summary>
    public static class FestaUiKit
    {
        // ── 팔레트 (킷의 파랑·주황·노랑에 맞춤) ────────────────────
        public static readonly Color Text     = Color.white;
        public static readonly Color Muted    = new(0.80f, 0.86f, 0.96f, 1f);
        public static readonly Color Ink      = new(0.05f, 0.11f, 0.22f, 1f);      // 외곽선·어두운 글자
        public static readonly Color Gold     = new(1f, 0.85f, 0.25f, 1f);
        public static readonly Color Good     = new(0.55f, 0.95f, 0.45f, 1f);
        public static readonly Color Bad      = new(1f, 0.45f, 0.40f, 1f);
        public static readonly Color Dim      = new(0.02f, 0.04f, 0.09f, 0.62f);    // 뒤 월드 암막
        public static readonly Color FallbackPanel = new(0.09f, 0.20f, 0.42f, 0.96f);

        const string FontResourcePath = "Fonts/MalgunGothic_SDF";
        static TMP_FontAsset s_font;
        static readonly Dictionary<int, Sprite> s_rounded = new();

        public static TMP_FontAsset Font()
        {
            if (s_font != null) return s_font;
            s_font = Resources.Load<TMP_FontAsset>(FontResourcePath);
            if (s_font == null)
            {
                Debug.LogError($"[FestaUiKit] Resources/{FontResourcePath} 를 찾지 못했다 — TMP 기본 폰트로 떨어진다(한글이 깨질 수 있다).");
                s_font = TMP_Settings.defaultFontAsset;
            }
            return s_font;
        }

        // ── 캔버스 ─────────────────────────────────────────────────

        /// <summary>1920×1080 기준으로 스케일되는 오버레이 캔버스. EventSystem 이 없으면 만든다.</summary>
        public static Canvas OverlayCanvas(Transform parent, string name, int sortingOrder)
        {
            if (Object.FindFirstObjectByType<EventSystem>() == null)
                new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));

            var go = new GameObject(name, typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            go.transform.SetParent(parent, false);
            var canvas = go.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.sortingOrder = sortingOrder;
            var scaler = go.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920, 1080);
            scaler.matchWidthOrHeight = 0.5f;
            return canvas;
        }

        /// <summary>뒤 월드를 어둡게 덮고 클릭이 새지 않게 하는 암막.</summary>
        public static Image Backdrop(Transform parent)
        {
            var go = new GameObject("Backdrop", typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var img = go.GetComponent<Image>();
            img.color = Dim;
            Stretch(img.rectTransform);
            return img;
        }

        // ── 배치 ───────────────────────────────────────────────────

        public static RectTransform Place(RectTransform rt, Vector2 anchor, Vector2 pivot, Vector2 pos, Vector2 size)
        {
            rt.anchorMin = rt.anchorMax = anchor;
            rt.pivot = pivot;
            rt.anchoredPosition = pos;
            rt.sizeDelta = size;
            return rt;
        }

        public static void Stretch(RectTransform rt)
        {
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.offsetMin = rt.offsetMax = Vector2.zero;
        }

        // ── 킷 스프라이트 ──────────────────────────────────────────

        /// <summary>킷 조각 하나를 Image 로. 경계가 있으면 Sliced, 아이콘은 Simple(비율 유지).</summary>
        public static Image Sprite(Transform parent, string name, UiSprite id, Color? tint = null)
        {
            var go = new GameObject(name, typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var img = go.GetComponent<Image>();
            var theme = FestaUiTheme.Instance;
            var sprite = theme != null ? theme.Get(id) : null;
            if (sprite != null)
            {
                img.sprite = sprite;
                bool sliced = FestaUiTheme.IsSliced(id);
                img.type = sliced ? Image.Type.Sliced : Image.Type.Simple;
                img.preserveAspect = !sliced;
                img.pixelsPerUnitMultiplier = 1.6f;   // 킷 원본이 크다 — 모서리를 화면 크기에 맞게 줄인다
            }
            else
            {
                img.sprite = Rounded(18);
                img.type = Image.Type.Sliced;
                if (tint == null) tint = FallbackPanel;
            }
            img.color = tint ?? Color.white;
            img.raycastTarget = true;
            return img;
        }

        /// <summary>패널(기본 파란 큰 패널). 위치는 <see cref="Place"/> 로.</summary>
        public static Image Panel(Transform parent, string name, UiSprite id = UiSprite.PanelBlue)
            => Sprite(parent, name, id);

        /// <summary>아이콘(비율 유지). 앵커 기본 위 가운데.</summary>
        public static Image Icon(RectTransform parent, UiSprite id, Vector2 pos, float size, Vector2? anchor = null, Vector2? pivot = null)
        {
            var img = Sprite(parent, "Icon_" + id, id);
            img.raycastTarget = false;
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(img.rectTransform, a, pivot ?? a, pos, new Vector2(size, size));
            return img;
        }

        // ── 글자 ───────────────────────────────────────────────────

        /// <summary>TMP 라벨. 기본 앵커는 부모의 위 가운데(피벗도) — 세로 좌표는 아래로 음수. <paramref name="outline"/> 0.15~0.3 이면 카툰 외곽선.</summary>
        public static TMP_Text Label(RectTransform parent, string text, float size, Vector2 pos, Vector2 box, Color color,
                                     FontStyles style = FontStyles.Normal, TextAlignmentOptions align = TextAlignmentOptions.Center,
                                     Vector2? anchor = null, Vector2? pivot = null, float outline = 0f)
        {
            var go = new GameObject("Label", typeof(RectTransform), typeof(TextMeshProUGUI));
            go.transform.SetParent(parent, false);
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(go.GetComponent<RectTransform>(), a, pivot ?? a, pos, box);
            var t = go.GetComponent<TextMeshProUGUI>();
            t.font = Font();
            t.fontSize = size;
            t.fontStyle = style;
            t.alignment = align;
            t.color = color;
            t.text = text;
            t.raycastTarget = false;
            t.enableWordWrapping = true;
            t.overflowMode = TextOverflowModes.Overflow;
            if (outline > 0f)
            {
                t.outlineWidth = outline;
                t.outlineColor = Ink;
            }
            return t;
        }

        /// <summary>제목 — 흰 굵은 글자 + 어두운 외곽선.</summary>
        public static TMP_Text Title(RectTransform parent, string text, float size, Vector2 pos, Vector2 box, Vector2? anchor = null, Vector2? pivot = null)
            => Label(parent, text, size, pos, box, Text, FontStyles.Bold, TextAlignmentOptions.Center, anchor, pivot, 0.22f);

        /// <summary>제목 배너(보라 줄무늬 알약) — 패널 위 테두리에 살짝 걸치게 두면 킷 데모와 같은 인상이 난다.</summary>
        public static Image TitleBanner(RectTransform parent, string text, Vector2 pos, Vector2 size, float fontSize = 28f, Vector2? anchor = null)
        {
            var banner = Sprite(parent, "TitleBanner", UiSprite.BannerPurpleStripe);
            banner.raycastTarget = false;
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(banner.rectTransform, a, a, pos, size);
            var t = Title(banner.rectTransform, text, fontSize, Vector2.zero, Vector2.zero);
            Stretch(t.rectTransform);
            return banner;
        }

        /// <summary>작은 알약 배지(어두운 남색). 글자 길이에 맞춰 폭을 잡는다.</summary>
        public static Image Chip(RectTransform parent, string text, Vector2 pos, Color? fg = null, Vector2? anchor = null, UiSprite bg = UiSprite.PillDark)
        {
            var img = Sprite(parent, "Chip", bg);
            img.raycastTarget = false;
            float w = Mathf.Max(56f, text.Length * 14f + 26f);
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(img.rectTransform, a, a, pos, new Vector2(w, 30f));
            var t = Label(img.rectTransform, text, 14f, Vector2.zero, Vector2.zero, fg ?? Gold, FontStyles.Bold);
            Stretch(t.rectTransform);
            return img;
        }

        // ── 버튼 ───────────────────────────────────────────────────

        /// <summary>킷 스프라이트 버튼. 기본은 주황(primary). 글자는 흰색 + 외곽선.</summary>
        public static Button SpriteButton(RectTransform parent, string label, Vector2 pos, Vector2 size, UnityEngine.Events.UnityAction onClick,
                                          UiSprite sprite = UiSprite.ButtonOrange, float fontSize = 22f,
                                          Vector2? anchor = null, Vector2? pivot = null)
        {
            var img = Sprite(parent, "Button", sprite);
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(img.rectTransform, a, pivot ?? a, pos, size);

            var btn = img.gameObject.AddComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors;
            colors.normalColor = Color.white;
            colors.highlightedColor = new Color(1.08f, 1.08f, 1.02f, 1f);
            colors.pressedColor = new Color(0.82f, 0.82f, 0.82f, 1f);
            colors.disabledColor = new Color(0.6f, 0.6f, 0.62f, 0.7f);
            colors.fadeDuration = 0.08f;
            btn.colors = colors;
            btn.onClick.AddListener(onClick);

            var t = Title(img.rectTransform, label, fontSize, Vector2.zero, Vector2.zero);
            Stretch(t.rectTransform);
            // 글자를 살짝 위로 — 킷 버튼은 아래쪽에 두꺼운 그림자가 있어 가운데 정렬이 아래로 처져 보인다.
            t.rectTransform.offsetMax = new Vector2(0f, -2f);
            t.rectTransform.offsetMin = new Vector2(0f, 6f);
            return btn;
        }

        /// <summary>아이콘만 있는 버튼(닫기 X 등).</summary>
        public static Button IconButton(RectTransform parent, UiSprite icon, Vector2 pos, float size, UnityEngine.Events.UnityAction onClick,
                                        Vector2? anchor = null, Vector2? pivot = null)
        {
            var img = Sprite(parent, "IconButton_" + icon, icon);
            var a = anchor ?? new Vector2(1f, 1f);
            Place(img.rectTransform, a, pivot ?? a, pos, new Vector2(size, size));
            var btn = img.gameObject.AddComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors;
            colors.highlightedColor = new Color(1.1f, 1.1f, 1.1f, 1f);
            colors.pressedColor = new Color(0.8f, 0.8f, 0.8f, 1f);
            btn.colors = colors;
            btn.onClick.AddListener(onClick);
            return btn;
        }

        public static TMP_Text ButtonLabel(Button button) => button.GetComponentInChildren<TMP_Text>();

        // ── 폴백: 코드 생성 둥근 사각형 ─────────────────────────────

        public static Sprite Rounded(int radius)
        {
            radius = Mathf.Clamp(radius, 2, 60);
            if (s_rounded.TryGetValue(radius, out var cached) && cached != null) return cached;

            int size = radius * 2 + 8;
            var tex = new Texture2D(size, size, TextureFormat.RGBA32, false) { wrapMode = TextureWrapMode.Clamp, filterMode = FilterMode.Bilinear };
            var px = new Color32[size * size];
            float r = radius;
            for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
            {
                float cx = x + 0.5f, cy = y + 0.5f;
                float dx = Mathf.Max(r - cx, cx - (size - r), 0f);
                float dy = Mathf.Max(r - cy, cy - (size - r), 0f);
                float d = Mathf.Sqrt(dx * dx + dy * dy);
                float alpha = Mathf.Clamp01(r - d + 0.5f);
                px[y * size + x] = new Color32(255, 255, 255, (byte)(alpha * 255f));
            }
            tex.SetPixels32(px);
            tex.Apply(false, true);
            var sprite = UnityEngine.Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0,
                                                   SpriteMeshType.FullRect, new Vector4(radius, radius, radius, radius));
            s_rounded[radius] = sprite;
            return sprite;
        }
    }
}
