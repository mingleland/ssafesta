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
    /// <para><b>v3 (2026-09-06)</b> — 밝은 카드 테마. 사용자 지적("킷이 촌스럽다", "인기 게임 UI 를 보고 예쁘게")에 따라
    /// 카툰 킷 스프라이트 대신 <b>코드로 그린 둥근 카드</b>로 돌아왔다. 기준은 Animal Crossing·Fall Guys·Zepeto 류의 캐주얼 인기작이
    /// 공유하는 문법 — 크림색 카드 + 큼직한 둥근 모서리 + 부드러운 그림자, 어두운 글자, 강조색 <b>하나</b>(코랄), 알약 버튼,
    /// 정보 최소·여백 넉넉. 킷은 아이콘(코인·닫기)만 빌린다.</para>
    ///
    /// <para>글자는 TextMeshPro 맑은고딕 SDF(WebGL 한글, T-22). 사용자가 게임용 글꼴(주아 등)을 주면 <see cref="Font"/> 하나만 바뀐다.</para>
    /// </summary>
    public static class FestaUiKit
    {
        // ── 팔레트 ─────────────────────────────────────────────────
        public static readonly Color Cream    = new(1f, 0.99f, 0.965f, 0.97f);       // 카드
        public static readonly Color Paper    = new(0.965f, 0.955f, 0.93f, 1f);      // 카드 안 2단계(표시창·보조 버튼)
        public static readonly Color Charcoal = new(0.12f, 0.13f, 0.18f, 0.96f);     // 어두운 표시창(숫자 강조용)
        public static readonly Color Line     = new(0f, 0f, 0f, 0.08f);
        public static readonly Color Shadow   = new(0f, 0f, 0f, 0.26f);
        public static readonly Color Accent   = new(1f, 0.44f, 0.38f, 1f);           // 코랄 — 강조는 이 하나
        public static readonly Color AccentDeep = new(0.86f, 0.33f, 0.28f, 1f);
        public static readonly Color Text     = new(0.16f, 0.16f, 0.20f, 1f);
        public static readonly Color Muted    = new(0.55f, 0.54f, 0.60f, 1f);
        public static readonly Color OnAccent = Color.white;
        public static readonly Color Gold     = new(0.98f, 0.76f, 0.30f, 1f);        // 코인 숫자(어두운 표시창 위)
        public static readonly Color Good     = new(0.20f, 0.68f, 0.42f, 1f);
        public static readonly Color Bad      = new(0.88f, 0.32f, 0.29f, 1f);
        public static readonly Color Dim      = new(0.05f, 0.05f, 0.08f, 0.42f);     // 뒤 월드 암막

        // 글꼴 두 벌 (2026-09-06, OFL — Resources/Fonts 에 라이선스 동봉). 본문은 Noto Sans KR Bold(가독), 제목·버튼·배지는 주아(Jua, 둥근 디스플레이).
        // 둘 다 Malgun 과 같은 Dynamic SDF(1024, 90pt) 라 WebGL 에서 런타임에 글리프를 채운다. 로비(캐릭터 커스터마이징)는 이 킷을 쓰지 않는다.
        const string FontResourcePath = "Fonts/NotoSansKRBold_SDF";
        const string DisplayFontResourcePath = "Fonts/Jua_SDF";
        const string LegacyFontResourcePath = "Fonts/MalgunGothic_SDF";
        static TMP_FontAsset s_font, s_displayFont;
        static readonly Dictionary<int, Sprite> s_rounded = new();

        public enum Card { Cream, Paper, Charcoal }

        /// <summary>본문 글꼴 — Noto Sans KR Bold. 없으면 맑은고딕 SDF, 그것도 없으면 TMP 기본.</summary>
        public static TMP_FontAsset Font()
        {
            if (s_font != null) return s_font;
            s_font = LoadFont(FontResourcePath) ?? LoadFont(LegacyFontResourcePath);
            if (s_font == null)
            {
                Debug.LogError($"[FestaUiKit] Resources/{FontResourcePath} 를 찾지 못했다 — TMP 기본 폰트로 떨어진다(한글이 깨질 수 있다).");
                s_font = TMP_Settings.defaultFontAsset;
            }
            return s_font;
        }

        /// <summary>제목·버튼·배지 글꼴 — 주아. 없으면 본문 글꼴.</summary>
        public static TMP_FontAsset DisplayFont()
        {
            if (s_displayFont != null) return s_displayFont;
            s_displayFont = LoadFont(DisplayFontResourcePath) ?? Font();
            return s_displayFont;
        }

        static TMP_FontAsset LoadFont(string path)
        {
            var f = Resources.Load<TMP_FontAsset>(path);
            if (f == null) Debug.LogWarning($"[FestaUiKit] Resources/{path} 없음.");
            return f;
        }

        /// <summary>
        /// 글꼴 자체가 Bold 인 페이스(Noto Bold·주아)에 TMP 가짜 굵기까지 얹으면 글자가 뭉친다 — Bold 플래그는 떼고 나머지 스타일만 남긴다.
        /// </summary>
        static FontStyles Normalize(FontStyles style) => style & ~FontStyles.Bold;

        // ── 캔버스 ─────────────────────────────────────────────────

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

        // ── 카드·패널 ──────────────────────────────────────────────

        /// <summary>둥근 카드. 크림(기본)·페이퍼(안쪽)·차콜(숫자 표시창). 크림·차콜은 그림자를 깐다.</summary>
        public static Image Panel(Transform parent, string name, Card style = Card.Cream, int radius = 26)
        {
            Color color = style == Card.Cream ? Cream : style == Card.Paper ? Paper : Charcoal;
            bool shadow = style != Card.Paper;

            var go = new GameObject(name, typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var img = go.GetComponent<Image>();
            img.sprite = Rounded(radius);
            img.type = Image.Type.Sliced;
            img.color = color;
            img.raycastTarget = true;

            if (shadow)
            {
                var sh = new GameObject(name + "_Shadow", typeof(RectTransform), typeof(Image));
                sh.transform.SetParent(parent, false);
                sh.transform.SetSiblingIndex(go.transform.GetSiblingIndex());
                var si = sh.GetComponent<Image>();
                si.sprite = Rounded(radius + 10);
                si.type = Image.Type.Sliced;
                si.color = Shadow;
                si.raycastTarget = false;
                var follower = sh.AddComponent<FollowRect>();
                follower.Target = img.rectTransform;
                follower.Offset = new Vector2(0f, -10f);
                follower.Grow = new Vector2(18f, 18f);
            }

            if (style != Card.Charcoal)
            {
                var o = go.AddComponent<Outline>();
                o.effectColor = Line;
                o.effectDistance = new Vector2(1f, -1f);
                o.useGraphicAlpha = true;
            }
            return img;
        }

        /// <summary>둥근 사각 이미지 하나(그림자 없음) — 구분선·태그 배경 등.</summary>
        public static Image Rect(Transform parent, string name, Color color, int radius = 12)
        {
            var go = new GameObject(name, typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var img = go.GetComponent<Image>();
            img.sprite = Rounded(radius);
            img.type = Image.Type.Sliced;
            img.color = color;
            img.raycastTarget = false;
            return img;
        }

        // ── 글자 ───────────────────────────────────────────────────

        /// <summary>TMP 라벨. 기본 앵커는 부모의 위 가운데(피벗도) — 세로 좌표는 아래로 음수.</summary>
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
            t.fontStyle = Normalize(style);
            t.alignment = align;
            t.color = color;
            t.text = text;
            t.raycastTarget = false;
            t.enableWordWrapping = true;
            t.overflowMode = TextOverflowModes.Overflow;
            if (outline > 0f)
            {
                t.outlineWidth = outline;
                t.outlineColor = new Color(0f, 0f, 0f, 0.85f);
            }
            return t;
        }

        /// <summary>제목 — 어두운 굵은 글자.</summary>
        public static TMP_Text Title(RectTransform parent, string text, float size, Vector2 pos, Vector2 box, Vector2? anchor = null, Vector2? pivot = null)
            => Display(Label(parent, text, size, pos, box, Text, FontStyles.Bold, TextAlignmentOptions.Center, anchor, pivot));

        /// <summary>라벨을 디스플레이 글꼴(주아)로 바꾼다 — 제목·버튼·배지에만.</summary>
        public static TMP_Text Display(TMP_Text t) { t.font = DisplayFont(); return t; }

        /// <summary>
        /// 카드 위쪽에 얹는 제목 태그 — 코랄 알약에 흰 굵은 글자. 카드 테두리에 반쯤 걸치게 두면 "이름표" 처럼 읽힌다.
        /// </summary>
        public static Image TitleBanner(RectTransform parent, string text, Vector2 pos, Vector2 size, float fontSize = 24f, Vector2? anchor = null)
        {
            var pill = Rect(parent, "TitleTag", Accent, Mathf.RoundToInt(size.y / 2f));
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(pill.rectTransform, a, a, pos, size);
            var t = Display(Label(pill.rectTransform, text, fontSize, Vector2.zero, Vector2.zero, OnAccent, FontStyles.Bold));
            Stretch(t.rectTransform);
            return pill;
        }

        /// <summary>작은 알약 배지(연한 회색 바탕, 작은 글자). <paramref name="fg"/> 기본은 보조 글자색.</summary>
        public static Image Chip(RectTransform parent, string text, Vector2 pos, Color? fg = null, Vector2? anchor = null, Color? bg = null)
        {
            var img = Rect(parent, "Chip", bg ?? Paper, 13);
            float w = Mathf.Max(56f, text.Length * 13f + 28f);
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(img.rectTransform, a, a, pos, new Vector2(w, 26f));
            var t = Display(Label(img.rectTransform, text, 13f, Vector2.zero, Vector2.zero, fg ?? Muted, FontStyles.Bold));
            Stretch(t.rectTransform);
            return img;
        }

        /// <summary>아이콘(킷 스프라이트, 비율 유지). 테마가 없으면 코랄 원.</summary>
        public static Image Icon(RectTransform parent, UiSprite id, Vector2 pos, float size, Vector2? anchor = null, Vector2? pivot = null)
        {
            var go = new GameObject("Icon_" + id, typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var img = go.GetComponent<Image>();
            var theme = FestaUiTheme.Instance;
            var sprite = theme != null ? theme.Get(id) : null;
            if (sprite != null) { img.sprite = sprite; img.preserveAspect = true; img.color = Color.white; }
            else { img.sprite = Rounded(30); img.type = Image.Type.Sliced; img.color = Accent; }
            img.raycastTarget = false;
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(img.rectTransform, a, pivot ?? a, pos, new Vector2(size, size));
            return img;
        }

        // ── 버튼 ───────────────────────────────────────────────────

        /// <summary>알약 버튼. <paramref name="primary"/> 코랄+흰 글자, 아니면 페이퍼+어두운 글자.</summary>
        public static Button PillButton(RectTransform parent, string label, Vector2 pos, Vector2 size, UnityEngine.Events.UnityAction onClick,
                                        bool primary = true, float fontSize = 20f, Vector2? anchor = null, Vector2? pivot = null)
        {
            var go = new GameObject("Button", typeof(RectTransform), typeof(Image), typeof(Button));
            go.transform.SetParent(parent, false);
            var a = anchor ?? new Vector2(0.5f, 1f);
            Place(go.GetComponent<RectTransform>(), a, pivot ?? a, pos, size);
            var img = go.GetComponent<Image>();
            img.sprite = Rounded(Mathf.RoundToInt(size.y / 2f));
            img.type = Image.Type.Sliced;
            img.color = primary ? Accent : Paper;

            var btn = go.GetComponent<Button>();
            btn.targetGraphic = img;
            var colors = btn.colors;
            colors.normalColor = Color.white;
            colors.highlightedColor = primary ? new Color(1.06f, 1.06f, 1.06f, 1f) : new Color(0.94f, 0.94f, 0.94f, 1f);
            colors.pressedColor = primary ? new Color(0.84f, 0.84f, 0.84f, 1f) : new Color(0.86f, 0.86f, 0.86f, 1f);
            colors.disabledColor = new Color(1f, 1f, 1f, 0.45f);
            colors.fadeDuration = 0.08f;
            btn.colors = colors;
            btn.onClick.AddListener(onClick);

            if (primary)
            {
                // 아래쪽 살짝 어두운 띠 — 눌리는 물성. 둥근 알약이 평면 스티커처럼 보이지 않게.
                var bottom = Rect(go.transform, "Depth", new Color(0f, 0f, 0f, 0.14f), Mathf.RoundToInt(size.y / 2f));
                var br = bottom.rectTransform;
                br.anchorMin = new Vector2(0f, 0f); br.anchorMax = new Vector2(1f, 0.5f);
                br.offsetMin = Vector2.zero; br.offsetMax = Vector2.zero;
            }

            var t = Display(Label(go.GetComponent<RectTransform>(), label, fontSize, Vector2.zero, Vector2.zero, primary ? OnAccent : Text, FontStyles.Bold));
            Stretch(t.rectTransform);
            t.rectTransform.offsetMin = new Vector2(0f, 2f);
            return btn;
        }

        /// <summary>닫기(✕) 원형 버튼 — 페이퍼 바탕에 어두운 글자.</summary>
        public static Button CloseButton(RectTransform parent, Vector2 pos, float size, UnityEngine.Events.UnityAction onClick,
                                         Vector2? anchor = null, Vector2? pivot = null)
        {
            // "×"(U+00D7) — 주아·Noto 모두 가진 글리프. "✕"(U+2715) 는 한글 글꼴에 없어 대체 글꼴로 빠질 수 있다.
            var btn = PillButton(parent, "×", pos, new Vector2(size, size), onClick, false, size * 0.6f, anchor ?? new Vector2(1f, 1f), pivot ?? new Vector2(1f, 1f));
            btn.name = "Close";
            return btn;
        }

        public static TMP_Text ButtonLabel(Button button) => button.GetComponentInChildren<TMP_Text>();

        // ── 둥근 사각형 스프라이트 (코드 생성, 9-slice) ─────────────

        public static Sprite Rounded(int radius)
        {
            radius = Mathf.Clamp(radius, 2, 64);
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
            var sprite = Sprite.Create(tex, new Rect(0, 0, size, size), new Vector2(0.5f, 0.5f), 100f, 0,
                                       SpriteMeshType.FullRect, new Vector4(radius, radius, radius, radius));
            sprite.name = "rounded_" + radius;
            s_rounded[radius] = sprite;
            return sprite;
        }

        /// <summary>IMGUI 용 — 둥근 사각형 텍스처와 그 경계(px). <see cref="Rounded"/> 와 같은 원본.</summary>
        public static Texture2D RoundedTexture(int radius, out int border)
        {
            var s = Rounded(radius);
            border = radius;
            return s.texture;
        }

        /// <summary>그림자가 패널을 따라가게 하는 작은 컴포넌트.</summary>
        public sealed class FollowRect : MonoBehaviour
        {
            public RectTransform Target;
            public Vector2 Offset;
            public Vector2 Grow;
            RectTransform _self;

            Image _image;

            void LateUpdate()
            {
                if (Target == null) return;
                _self ??= GetComponent<RectTransform>();
                // 그림자는 카드의 **형제**라(자식이면 카드 위에 그려진다) 카드를 SetActive(false) 해도 혼자 남는다 —
                // 슬롯머신 팝업 자리에 회색 반투명 사각형이 항상 떠 있던 원인(2026-09-06 WebGL 실측). 대상 상태를 따라간다.
                _image ??= GetComponent<Image>();
                if (_image != null) _image.enabled = Target.gameObject.activeInHierarchy;
                _self.anchorMin = Target.anchorMin;
                _self.anchorMax = Target.anchorMax;
                _self.pivot = Target.pivot;
                _self.anchoredPosition = Target.anchoredPosition + Offset;
                _self.sizeDelta = Target.sizeDelta + Grow;
            }
        }
    }
}
