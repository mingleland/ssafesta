using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 상호작용 화면 표현의 **단일 구현**. 부스 입장(포털)과 부스 오브젝트(노트북·AI 직원)가
    /// 같은 화면 언어를 쓰게 한다.
    ///
    /// <para>전에는 둘이 달랐다 — 포털은 키캡 패널(IMGUI), 오브젝트는 화면 하단 uGUI 텍스트.
    /// **같은 F 조작인데 화면이 달라 다른 기능처럼 보였다** (S15P21A604-355 사용자 보고).
    /// 그리는 쪽을 여기 한 곳으로 모아, 한쪽만 고쳐서 다시 어긋나는 일을 막는다.</para>
    ///
    /// <para>화면 중앙 살짝 아래에 고정한다. 대상 위 월드 앵커 방식은 3인칭 하향 카메라에서
    /// 화면 밖으로 나갔다 — 부스 높이·카메라 각도와 무관하게 보여야 한다.</para>
    /// </summary>
    public static class InteractPromptUI
    {
        static GUIStyle _labelStyle;
        static GUIStyle _capStyle;
        static GUIStyle _toastStyle;
        static Texture2D _panelTex;
        static Texture2D _capTex;

        /// <summary>키캡 프롬프트 한 줄. 예: <c>F — 상호작용</c> 대신 <c>[F] 노트북 열기</c>.</summary>
        public static void DrawPrompt(string label)
        {
            if (string.IsNullOrEmpty(label)) return;
            EnsureStyles();
            float ui = Screen.height / 1080f;

            _labelStyle.fontSize = Mathf.RoundToInt(19f * ui);
            _capStyle.fontSize = Mathf.RoundToInt(18f * ui);

            float cap = 30f * ui;                       // 키캡 한 변
            float labelW = _labelStyle.CalcSize(new GUIContent(label)).x;
            float pad = 10f * ui;
            float w = cap + pad * 3f + labelW;
            float h = cap + pad * 1.4f;
            float x = (Screen.width - w) / 2f;
            float y = Screen.height * 0.52f;

            GUI.DrawTexture(new Rect(x, y, w, h), PanelTexture(), ScaleMode.StretchToFill);
            GUI.DrawTexture(new Rect(x + pad, y + (h - cap) / 2f, cap, cap), CapTexture(), ScaleMode.StretchToFill);
            GUI.Label(new Rect(x + pad, y + (h - cap) / 2f, cap, cap), "F", _capStyle);
            GUI.Label(new Rect(x + pad * 2f + cap, y, labelW + pad, h), label, _labelStyle);
        }

        /// <summary>
        /// 짧은 알림 — 프롬프트 **바로 위**에 같은 패널로 그린다.
        /// 노트북·AI 상호작용의 가시 결과(홈페이지·대화창)는 웹 화면 몫이라, 월드 단독 실행에서는
        /// 이것이 유일한 "보냈다" 신호다 (S15P21A604-348).
        /// </summary>
        public static void DrawToast(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            EnsureStyles();
            float ui = Screen.height / 1080f;
            _toastStyle.fontSize = Mathf.RoundToInt(17f * ui);

            float pad = 10f * ui;
            float w = _toastStyle.CalcSize(new GUIContent(text)).x + pad * 2f;
            float h = 26f * ui + pad;
            float x = (Screen.width - w) / 2f;
            float y = Screen.height * 0.52f - h - 8f * ui;   // 프롬프트 위

            GUI.DrawTexture(new Rect(x, y, w, h), PanelTexture(), ScaleMode.StretchToFill);
            GUI.Label(new Rect(x, y, w, h), text, _toastStyle);
        }

        static void EnsureStyles()
        {
            // GUIStyle 을 매 프레임 new 하면 프롬프트가 떠 있는 내내 GC 쓰레기가 쌓여
            // 주기적 GC 스파이크(끊김)에 일조한다 — 한 번 만들어 캐시한다.
            if (_labelStyle != null) return;

            _labelStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleLeft,
                fontStyle = FontStyle.Bold,
            };
            _labelStyle.normal.textColor = Color.white;

            _capStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                fontStyle = FontStyle.Bold,
            };
            _capStyle.normal.textColor = new Color(0.12f, 0.12f, 0.12f);

            _toastStyle = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                fontStyle = FontStyle.Bold,
            };
            _toastStyle.normal.textColor = new Color(0.62f, 1f, 0.72f);
        }

        static Texture2D PanelTexture()
        {
            if (_panelTex == null) _panelTex = Solid(new Color(0.07f, 0.07f, 0.09f, 0.82f));
            return _panelTex;
        }

        static Texture2D CapTexture()
        {
            if (_capTex == null) _capTex = Solid(new Color(0.93f, 0.93f, 0.9f, 0.98f));
            return _capTex;
        }

        static Texture2D Solid(Color c)
        {
            var t = new Texture2D(2, 2, TextureFormat.RGBA32, false);
            t.SetPixels(new[] { c, c, c, c });
            t.Apply();
            return t;
        }
    }

    /// <summary>
    /// 대상 발밑의 부드러운 링. **로컬 오브젝트라 본인 화면에만 보인다** — 네트워크로 동기화하지
    /// 않는다(다른 사람 화면에 남의 조준선이 뜨면 안 된다).
    ///
    /// 포털과 부스 오브젝트가 같은 링을 쓰도록 여기로 옮겼다.
    /// </summary>
    public sealed class InteractRing
    {
        static Texture2D s_tex;

        GameObject _go;
        Material _mat;

        public void Show(Vector3 groundPos, float radius)
        {
            if (_go == null) Create();
            _go.SetActive(true);
            _go.transform.position = groundPos;
            float pulse = 1f + 0.06f * Mathf.Sin(Time.time * 4.2f);
            _go.transform.localScale = new Vector3(radius * 2f * pulse, 1f, radius * 2f * pulse);
        }

        public void Hide()
        {
            if (_go != null) _go.SetActive(false);
        }

        public void Dispose()
        {
            if (_go != null) Object.Destroy(_go);
            if (_mat != null) Object.Destroy(_mat);
            _go = null;
            _mat = null;
        }

        void Create()
        {
            _go = GameObject.CreatePrimitive(PrimitiveType.Quad);
            Object.Destroy(_go.GetComponent<Collider>());
            _go.name = "InteractHighlight (local)";
            _go.transform.rotation = Quaternion.Euler(90f, 0f, 0f);
            _mat = new Material(Shader.Find("Mobile/Particles/Additive")) { mainTexture = Texture() };
            var r = _go.GetComponent<Renderer>();
            r.sharedMaterial = _mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        static Texture2D Texture()
        {
            if (s_tex != null) return s_tex;
            const int S = 128;
            s_tex = new Texture2D(S, S, TextureFormat.RGBA32, false);
            for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
            {
                float d = Vector2.Distance(new Vector2(x, y), new Vector2(S / 2f, S / 2f)) / (S / 2f);
                // 가장자리 링 + 안쪽 은은한 채움
                float ring = Mathf.Exp(-Mathf.Pow((d - 0.82f) / 0.08f, 2f));
                float fill = d < 0.82f ? 0.10f * (1f - d) : 0f;
                float a = Mathf.Clamp01(ring * 0.85f + fill);
                s_tex.SetPixel(x, y, new Color(1f, 0.82f, 0.35f, 1f) * a);
            }
            s_tex.Apply();
            return s_tex;
        }
    }
}
