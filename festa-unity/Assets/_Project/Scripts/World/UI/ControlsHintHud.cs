using Festa.Integration;
using Festa.Network;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World.UI
{
    /// <summary>
    /// 월드 조작 안내 (S15P21A604-451). 첫 스폰 직후 15초 동안 조작 카드를 보여 주고, 그 뒤에는 화면 왼쪽 아래
    /// 작은 알약 <c>[H] 조작 안내</c> 만 남긴다. H 로 언제든 다시 열고 닫는다.
    ///
    /// <para>왜 필요한가 — 사용자 테스트(09-08)에 처음 오는 사람은 WASD·F·Esc 를 모른다. 월드 어디에도 안내가 없었다
    /// (2026-09-06 재점검). 로비 UI 는 그대로 두고 **월드 화면에만** 그린다 — 로컬 플레이어(<see cref="PlayerMovement.IsOwner"/>)가
    /// 있을 때만 켜지므로 로비 씬에서는 아무것도 그리지 않는다.</para>
    ///
    /// <para>미니게임 HUD·오버레이(<see cref="InputBridge.IsLocked"/>)·초점 카메라(<see cref="InteractionFocusCamera.IsFocused"/>) 중에는
    /// 숨긴다 — 그 화면들이 자기 조작을 따로 안내한다. 그리기는 <see cref="InteractPromptUI"/> 의 카드·키캡 조각을 그대로 써서
    /// 프롬프트·토스트와 같은 화면 언어를 유지한다. 서버(-batchmode)에는 만들지 않는다.</para>
    /// </summary>
    public sealed class ControlsHintHud : MonoBehaviour
    {
        public const string ObjectName = "@ControlsHint";
        const float AutoShowSeconds = 15f;
        const float OwnerScanInterval = 0.5f;

        static readonly (string key, string label)[] Rows =
        {
            ("W A S D", "이동"),
            ("Shift", "달리기"),
            ("Space", "점프"),
            ("우클릭 드래그", "시야 돌리기"),
            ("F", "상호작용 · 부스 입장"),
            ("Esc", "화면 닫기 · 나가기"),
            ("Alt + 클릭", "감정 표현"),
            ("H", "이 안내 숨기기 / 보기"),
        };

        PlayerMovement _owner;
        float _nextOwnerScan;
        bool _seenPlayer;
        bool _open;
        float _autoHideAt = -1f;

        /// <summary>테스트·진단용 — 카드가 열려 있는가.</summary>
        public bool IsOpen => _open;
        public static ControlsHintHud Instance { get; private set; }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
#if UNITY_SERVER
            return;
#else
            if (Application.isBatchMode) return;
            if (Instance != null || GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            Instance = go.AddComponent<ControlsHintHud>();
            DontDestroyOnLoad(go);
#endif
        }

        void OnDestroy() { if (Instance == this) Instance = null; }

        void Update()
        {
            if (_owner == null || !_owner.IsSpawned)
            {
                _owner = null;
                if (Time.unscaledTime < _nextOwnerScan) return;
                _nextOwnerScan = Time.unscaledTime + OwnerScanInterval;
                foreach (var pm in FindObjectsByType<PlayerMovement>(FindObjectsSortMode.None))
                    if (pm.IsOwner) { _owner = pm; break; }
                if (_owner == null) return;

                // 세션에서 처음 스폰됐을 때만 자동으로 펼친다 — 재접속·재스폰마다 다시 뜨면 성가시다.
                if (!_seenPlayer)
                {
                    _seenPlayer = true;
                    _open = true;
                    _autoHideAt = Time.unscaledTime + AutoShowSeconds;
                }
            }

            if (_autoHideAt > 0f && Time.unscaledTime >= _autoHideAt) { _open = false; _autoHideAt = -1f; }

            var kb = Keyboard.current;
            if (kb != null && kb.hKey.wasPressedThisFrame && !InputBridge.IsLocked)
            {
                _open = !_open;
                _autoHideAt = -1f;   // 사용자가 손댔으면 자동 닫힘은 없다
            }
        }

        void OnGUI()
        {
            if (_owner == null) return;
            if (InputBridge.IsLocked || InteractionFocusCamera.IsFocused) return;

            float ui = Screen.height / 1080f;
            float margin = Mathf.Round(24f * ui);

            if (!_open) { DrawChip(ui, margin); return; }
            DrawCard(ui, margin);
        }

        /// <summary>닫힌 상태 — 왼쪽 아래 작은 알약 <c>[H] 조작 안내</c>.</summary>
        static void DrawChip(float ui, float margin)
        {
            int font = Mathf.RoundToInt(18f * ui);
            float h = Mathf.Round(36f * ui);
            float cap = Mathf.Round(28f * ui);
            float pad = Mathf.Round(10f * ui);
            float labelW = InteractPromptUI.MeasureLabel("조작 안내", font);
            float w = pad + cap + pad * 0.8f + labelW + pad;
            float x = margin, y = Screen.height - margin - h;

            InteractPromptUI.DrawCard(new Rect(x, y, w, h), Mathf.RoundToInt(h / 2f), new Color(1f, 0.99f, 0.965f, 0.9f));
            InteractPromptUI.DrawKeycap(new Rect(x + pad, y + (h - cap) / 2f, cap, cap), "H", Mathf.RoundToInt(16f * ui));
            InteractPromptUI.DrawLabel(new Rect(x + pad + cap + pad * 0.8f, y, labelW + 4f, h), "조작 안내", font, FestaUiKit.Text);
        }

        /// <summary>열린 상태 — 왼쪽 아래 조작 카드.</summary>
        static void DrawCard(float ui, float margin)
        {
            int font = Mathf.RoundToInt(20f * ui);
            int capFont = Mathf.RoundToInt(17f * ui);
            int titleFont = Mathf.RoundToInt(24f * ui);
            float rowH = Mathf.Round(40f * ui);
            float capH = Mathf.Round(30f * ui);
            float pad = Mathf.Round(18f * ui);
            float gap = Mathf.Round(12f * ui);
            float titleH = Mathf.Round(44f * ui);

            float keyW = 0f, labelW = 0f;
            foreach (var (key, label) in Rows)
            {
                keyW = Mathf.Max(keyW, InteractPromptUI.MeasureLabel(key, capFont) + Mathf.Round(20f * ui));
                labelW = Mathf.Max(labelW, InteractPromptUI.MeasureLabel(label, font));
            }
            keyW = Mathf.Max(keyW, capH);

            float w = pad + keyW + gap + labelW + pad;
            float h = pad * 0.6f + titleH + Rows.Length * rowH + pad * 0.8f;
            float x = margin, y = Screen.height - margin - h;

            InteractPromptUI.DrawCard(new Rect(x, y, w, h), Mathf.RoundToInt(22f * ui), FestaUiKit.Cream);
            InteractPromptUI.DrawLabel(new Rect(x + pad, y + pad * 0.6f, w - pad * 2f, titleH), "조작 방법", titleFont, FestaUiKit.Text);

            float ry = y + pad * 0.6f + titleH;
            foreach (var (key, label) in Rows)
            {
                InteractPromptUI.DrawKeycap(new Rect(x + pad, ry + (rowH - capH) / 2f, keyW, capH), key, capFont);
                InteractPromptUI.DrawLabel(new Rect(x + pad + keyW + gap, ry, labelW + 4f, rowH), label, font, FestaUiKit.Text);
                ry += rowH;
            }
        }
    }
}
