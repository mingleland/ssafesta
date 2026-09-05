using Festa.World.UI;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 상호작용 화면 표현의 **단일 구현**. 부스 입장(포털)과 부스 오브젝트(노트북·AI 직원·슬롯머신·게임기)가
    /// 같은 화면 언어를 쓴다 (S15P21A604-355).
    ///
    /// <para>2026-09-06 v3: 흰 알약 판 + 코랄 키캡 + 어두운 글자(다른 화면과 같은 카드 문법). 판은 코드 생성 둥근 텍스처를 3×3 조각으로 그려 모서리가 늘어나지 않는다.
    /// IMGUI 를 유지하는 이유 — 호출 계약이 "매 프레임 그린다"(<c>OnGUI</c> 에서 <see cref="DrawPrompt"/>) 라 uGUI 로 바꾸면
    /// 호출자 둘(BoothInteractionInput·PortalInteractor)을 함께 바꿔야 하고, 지금 필요한 것은 생김새다.</para>
    ///
    /// <para>좌표는 정수 픽셀로 맞춘다 — 소수 좌표는 글리프를 반 픽셀 번지게 한다(사용자 지적, -355).</para>
    /// </summary>
    public static class InteractPromptUI
    {
        static GUIStyle _labelStyle;
        static GUIStyle _capStyle;
        static GUIStyle _toastStyle;
        static readonly GUIContent s_measure = new();

        static GUIContent Measure(string text) { s_measure.text = text; return s_measure; }

        static Rect Snap(float x, float y, float w, float h) =>
            new Rect(Mathf.Round(x), Mathf.Round(y), Mathf.Round(w), Mathf.Round(h));

        /// <summary>키캡 프롬프트 한 줄 — <c>[F] 슬롯머신 (10코인)</c>. 화면 중앙 살짝 아래 고정.</summary>
        public static void DrawPrompt(string label)
        {
            if (string.IsNullOrEmpty(label)) return;
            EnsureStyles();
            float ui = Screen.height / 1080f;
            _labelStyle.fontSize = Mathf.RoundToInt(24f * ui);
            _capStyle.fontSize = Mathf.RoundToInt(24f * ui);
            float cap = Mathf.Round(46f * ui);
            float padX = Mathf.Round(18f * ui);
            float labelW = Mathf.Round(_labelStyle.CalcSize(Measure(label)).x);
            float h = Mathf.Round(56f * ui);
            float w = padX + cap + Mathf.Round(12f * ui) + labelW + padX;
            float x = Mathf.Round((Screen.width - w) / 2f);
            float y = Mathf.Round(Screen.height * 0.52f);

            DrawRounded(Snap(x, y, w, h), Mathf.RoundToInt(h / 2f), new Color(1f, 0.99f, 0.965f, 0.96f));
            var capRect = Snap(x + padX, y + (h - cap) / 2f, cap, cap);
            DrawRounded(capRect, Mathf.RoundToInt(cap * 0.3f), FestaUiKit.Accent);
            PlainLabel(capRect, "F", _capStyle, Color.white);
            PlainLabel(Snap(capRect.xMax + 12f * ui, y, labelW + 4f, h), label, _labelStyle, FestaUiKit.Text);
        }

        /// <summary>짧은 알림 — 프롬프트 바로 위, 같은 남색 알약. 월드 단독 실행에서 "보냈다" 를 알리는 유일한 신호 (S15P21A604-348).</summary>
        public static void DrawToast(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            EnsureStyles();
            float ui = Screen.height / 1080f;
            _toastStyle.fontSize = Mathf.RoundToInt(20f * ui);
            float pad = Mathf.Round(16f * ui);
            float w = Mathf.Round(_toastStyle.CalcSize(Measure(text)).x) + pad * 2f;
            float h = Mathf.Round(40f * ui);
            float x = Mathf.Round((Screen.width - w) / 2f);
            float y = Mathf.Round(Screen.height * 0.52f - h - 10f * ui);
            DrawRounded(Snap(x, y, w, h), Mathf.RoundToInt(h / 2f), new Color(0.12f, 0.13f, 0.18f, 0.92f));
            PlainLabel(Snap(x, y, w, h), text, _toastStyle, Color.white);
        }

        static void PlainLabel(Rect rect, string text, GUIStyle style, Color color)
        {
            var prev = style.normal.textColor;
            style.normal.textColor = color;
            GUI.Label(rect, text, style);
            style.normal.textColor = prev;
        }

        /// <summary>어두운 그림자 한 겹 위에 글자. 오프셋은 정수 1픽셀.</summary>
        static void ShadowedLabel(Rect rect, string text, GUIStyle style, Color color)
        {
            var prev = style.normal.textColor;
            style.normal.textColor = new Color(0f, 0f, 0f, 0.8f);
            GUI.Label(new Rect(rect.x + 1f, rect.y + 1f, rect.width, rect.height), text, style);
            GUI.Label(new Rect(rect.x - 1f, rect.y + 1f, rect.width, rect.height), text, style);
            GUI.Label(new Rect(rect.x + 1f, rect.y - 1f, rect.width, rect.height), text, style);
            GUI.Label(new Rect(rect.x - 1f, rect.y - 1f, rect.width, rect.height), text, style);
            style.normal.textColor = color;
            GUI.Label(rect, text, style);
            style.normal.textColor = prev;
        }

        /// <summary>
        /// 코드 생성 둥근 사각형 텍스처를 IMGUI 로 3×3 9-slice 그리기 — 모서리 반지름은 화면 픽셀 그대로.
        /// </summary>
        static void DrawRounded(Rect dst, int radius, Color tint)
        {
            radius = Mathf.Clamp(radius, 2, Mathf.FloorToInt(Mathf.Min(dst.width, dst.height) / 2f));
            var tex = FestaUiKit.RoundedTexture(radius, out int bpx);
            var px = new Rect(0f, 0f, tex.width, tex.height);
            var border = new Vector4(bpx, bpx, bpx, bpx);
            float l = bpx, r = bpx, b = bpx, t = bpx;

            // 텍스처 UV (원점 좌하단). px 는 아틀라스 픽셀 rect.
            float tw = tex.width, th = tex.height;
            float u0 = px.xMin / tw, u1 = (px.xMin + border.x) / tw, u2 = (px.xMax - border.z) / tw, u3 = px.xMax / tw;
            float v0 = px.yMin / th, v1 = (px.yMin + border.y) / th, v2 = (px.yMax - border.w) / th, v3 = px.yMax / th;

            // 화면 x/y (원점 좌상단). 위 행이 텍스처의 위(v2~v3).
            float x0 = dst.xMin, x1 = dst.xMin + l, x2 = dst.xMax - r, x3 = dst.xMax;
            float y0 = dst.yMin, y1 = dst.yMin + t, y2 = dst.yMax - b, y3 = dst.yMax;

            var prevColor = GUI.color; GUI.color = tint;
            Piece(tex, x0, y0, x1 - x0, y1 - y0, u0, v2, u1, v3);
            Piece(tex, x1, y0, x2 - x1, y1 - y0, u1, v2, u2, v3);
            Piece(tex, x2, y0, x3 - x2, y1 - y0, u2, v2, u3, v3);
            Piece(tex, x0, y1, x1 - x0, y2 - y1, u0, v1, u1, v2);
            Piece(tex, x1, y1, x2 - x1, y2 - y1, u1, v1, u2, v2);
            Piece(tex, x2, y1, x3 - x2, y2 - y1, u2, v1, u3, v2);
            Piece(tex, x0, y2, x1 - x0, y3 - y2, u0, v0, u1, v1);
            Piece(tex, x1, y2, x2 - x1, y3 - y2, u1, v0, u2, v1);
            Piece(tex, x2, y2, x3 - x2, y3 - y2, u2, v0, u3, v1);
            GUI.color = prevColor;
        }

        static void Piece(Texture2D tex, float x, float y, float w, float h, float u0, float v0, float u1, float v1)
        {
            if (w <= 0f || h <= 0f) return;
            GUI.DrawTextureWithTexCoords(new Rect(x, y, w, h), tex, new Rect(u0, v0, u1 - u0, v1 - v0), true);
        }

        static void EnsureStyles()
        {
            if (_labelStyle != null) return;
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            _labelStyle = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleLeft, fontStyle = FontStyle.Normal, wordWrap = false, richText = false };
            _capStyle = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleCenter, fontStyle = FontStyle.Normal, wordWrap = false, richText = false };
            _toastStyle = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleCenter, fontStyle = FontStyle.Normal, wordWrap = false, richText = false };
            if (font != null) { _labelStyle.font = font; _capStyle.font = font; _toastStyle.font = font; }
            _labelStyle.normal.textColor = _capStyle.normal.textColor = _toastStyle.normal.textColor = Color.white;
        }
    }
}
