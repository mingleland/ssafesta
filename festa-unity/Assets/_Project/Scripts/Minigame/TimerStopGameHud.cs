using Festa.Integration;
using UnityEngine;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Festa.Minigame
{
    /// <summary>
    /// 타이머 정지 게임의 화면 (spec 014 FR-001c, T013).
    ///
    /// ── IMGUI 를 쓰지 않는 이유 ───────────────────────────────
    /// tasks.md T013 은 "WebGL IMGUI 한글 미표시(T-22)" 때문에 영문 라벨이나 React 오버레이를
    /// 검토하라고 적혀 있다. 그런데 그 제약은 **IMGUI 한정**이고, 이 프로젝트의 캐릭터 로비는
    /// 이미 uGUI + `Resources/Fonts/MalgunGothicLight` 로 웹 빌드에서 한글을 정상 출력한다.
    /// 같은 스택을 쓰면 한글 UI 를 쓰면서 FE 의존도 없앨 수 있어 그쪽을 골랐다.
    ///
    /// ── 씬을 고치지 않는다 ────────────────────────────────────
    /// 캔버스를 전부 코드로 세운다. 프리팹·씬 편집이 없으니 머지 충돌이 나지 않고,
    /// 게임을 안 켠 사람의 씬에는 아무 것도 남지 않는다 (입장 게이트와 같은 방식).
    /// </summary>
    public sealed class TimerStopGameHud : MonoBehaviour
    {
        static readonly Color Backdrop = new(0.03f, 0.035f, 0.05f, 0.93f);
        static readonly Color Panel = new(0.075f, 0.08f, 0.10f, 0.99f);
        static readonly Color Card = new(0.145f, 0.155f, 0.185f, 0.99f);
        static readonly Color Border = new(0.34f, 0.35f, 0.39f, 0.94f);
        static readonly Color Accent = new(0.94f, 0.62f, 0.29f, 1f);
        static readonly Color TextMain = new(0.98f, 0.955f, 0.91f, 1f);
        static readonly Color TextMuted = new(0.74f, 0.72f, 0.69f, 1f);
        static readonly Color Good = new(0.45f, 0.85f, 0.55f, 1f);
        static readonly Color Bad = new(0.92f, 0.45f, 0.42f, 1f);

        static Font s_font;

        TimerStopGame _game;
        Text _target, _timer, _result, _hint, _verdict;
        Button _action;
        Text _actionLabel;

        /// <summary>게임 화면을 띄운다. 이미 떠 있으면 그것을 돌려준다.</summary>
        public static TimerStopGameHud Open(IGameResultClient client)
        {
            var existing = FindFirstObjectByType<TimerStopGameHud>();
            if (existing != null) return existing;

            var go = new GameObject("@TimerStopGameHud");
            var hud = go.AddComponent<TimerStopGameHud>();
            hud.Build(client);
            return hud;
        }

        public void Close()
        {
            _game?.Abort();
            Destroy(gameObject);
        }

        void Build(IGameResultClient client)
        {
            _game = new TimerStopGame(client);
            _game.Changed += Redraw;

            if (FindFirstObjectByType<EventSystem>() == null)
                new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));

            var canvasGo = new GameObject("Canvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            canvasGo.transform.SetParent(transform, false);
            var canvas = canvasGo.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            // 월드 UI 위에 확실히 올린다. 게임 중에는 이게 최상단이어야 한다.
            canvas.sortingOrder = 500;
            var scaler = canvasGo.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920, 1080);
            scaler.matchWidthOrHeight = 0.5f;

            // 뒤 월드를 눌러 상호작용이 새는 것을 막는 암막.
            var dim = MakeImage(canvasGo.transform, "Backdrop", Backdrop);
            Stretch(dim.rectTransform);

            var panel = MakeImage(dim.transform, "Panel", Panel);
            var pr = panel.rectTransform;
            pr.anchorMin = pr.anchorMax = new Vector2(0.5f, 0.5f);
            pr.pivot = new Vector2(0.5f, 0.5f);
            pr.sizeDelta = new Vector2(720, 620);
            AddOutline(panel.gameObject);

            Label(pr, "타이밍 스톱", 44, new Vector2(0, -56), 60, FontStyle.Bold, TextMain, 520f);
            _target = Label(pr, "", 30, new Vector2(0, -128), 42, FontStyle.Normal, TextMuted);

            // 타이머는 이 화면의 주인공이라 제일 크게.
            _timer = Label(pr, "0.00", 132, new Vector2(0, -196), 150, FontStyle.Bold, Accent);

            _result = Label(pr, "", 34, new Vector2(0, -358), 50, FontStyle.Bold, TextMain);
            _verdict = Label(pr, "", 22, new Vector2(0, -408), 34, FontStyle.Normal, TextMuted);

            _action = MakeButton(pr, "시작", new Vector2(0, -462), new Vector2(300, 62), OnAction);
            _actionLabel = _action.GetComponentInChildren<Text>();

            _hint = Label(pr, "Space 로도 시작·정지할 수 있습니다 · Esc 로 나가기", 18,
                          new Vector2(0, -534), 28, FontStyle.Normal, TextMuted);

            var close = MakeButton(pr, "나가기", Vector2.zero, new Vector2(96, 40), Close);
            close.GetComponentInChildren<Text>().fontSize = 17;
            // 패널 우상단 고정. 좌표를 직접 주면 패널 크기가 바뀔 때마다 다시 맞춰야 한다.
            var cr = close.GetComponent<RectTransform>();
            cr.anchorMin = cr.anchorMax = cr.pivot = new Vector2(1f, 1f);
            cr.anchoredPosition = new Vector2(-18f, -18f);

            Redraw();
        }

        void OnAction()
        {
            switch (_game.Current)
            {
                case TimerStopGame.Phase.Idle:
                case TimerStopGame.Phase.Failed:
                case TimerStopGame.Phase.Finished:
                    _game.Start();
                    break;
                case TimerStopGame.Phase.Running:
                    _game.Stop();
                    break;
            }
        }

        void Update()
        {
            // 판정에 쓰는 시간이라 timeScale 에 흔들리면 안 된다.
            _game.Tick(Time.unscaledDeltaTime);

            if (PressedThisFrame(KeyCode.Escape)) { Close(); return; }
            if (PressedThisFrame(KeyCode.Space)) OnAction();
        }

        /// <summary>
        /// 이번 프레임에 눌렸는지. **새 Input System 을 우선한다** — 이 프로젝트는 T-166 이후
        /// 레거시 입력에 의존하지 않는 것을 규칙으로 삼았고, 지금 백엔드는 Both 라 둘 다 살아 있다.
        /// 레거시 전용 빌드로 바뀌어도 아래 분기가 받아 준다.
        /// </summary>
        static bool PressedThisFrame(KeyCode key)
        {
#if ENABLE_INPUT_SYSTEM
            var keyboard = Keyboard.current;
            if (keyboard != null)
            {
                return key switch
                {
                    KeyCode.Escape => keyboard.escapeKey.wasPressedThisFrame,
                    KeyCode.Space => keyboard.spaceKey.wasPressedThisFrame,
                    _ => false,
                };
            }
#endif
#if ENABLE_LEGACY_INPUT_MANAGER
            return Input.GetKeyDown(key);
#else
            return false;
#endif
        }

        void Redraw()
        {
            switch (_game.Current)
            {
                case TimerStopGame.Phase.Idle:
                    _target.text = "목표 시간은 시작할 때 정해집니다";
                    _timer.text = "0.00";
                    _timer.color = Accent;
                    _result.text = "";
                    _verdict.text = "";
                    SetAction("시작", true);
                    break;

                case TimerStopGame.Phase.Starting:
                    _target.text = "목표 시간을 받아오는 중…";
                    SetAction("시작", false);
                    break;

                case TimerStopGame.Phase.Running:
                    _target.text = $"목표  {_game.TargetSeconds:F2}초";
                    _timer.text = _game.Elapsed.ToString("F2");
                    _timer.color = Accent;
                    _result.text = "";
                    _verdict.text = "";
                    SetAction("정지", true);
                    break;

                case TimerStopGame.Phase.Finished:
                    _target.text = $"목표  {_game.TargetSeconds:F2}초";
                    _timer.text = _game.StoppedSeconds.ToString("F2");
                    if (_game.TimedOut)
                    {
                        _timer.color = Bad;
                        _result.text = "실패 — 제한 시간을 넘겼습니다";
                        _result.color = Bad;
                    }
                    else
                    {
                        // 오차만 보여준다. 등급·점수는 보상 정책이 정해진 뒤의 일이다 (C-03).
                        _timer.color = Good;
                        _result.text = $"오차  {_game.ErrorSeconds:F3}초";
                        _result.color = TextMain;
                    }
                    _verdict.text = _game.Verdict != null ? _game.Verdict.message : "결과 전송 중…";
                    SetAction("다시 하기", true);
                    break;

                case TimerStopGame.Phase.Failed:
                    _target.text = "";
                    _timer.text = "--";
                    _timer.color = Bad;
                    _result.text = _game.FailureReason;
                    _result.color = Bad;
                    _verdict.text = "";
                    SetAction("다시 시도", true);
                    break;
            }
        }

        void SetAction(string label, bool interactable)
        {
            _actionLabel.text = label;
            _action.interactable = interactable;
        }

        // ── uGUI 조립 도우미 (로비와 같은 방식) ──────────────────

        static Font Font()
        {
            // 로비와 같은 폰트. 없으면 내장 폰트로 떨어지되 한글은 깨진다 — 조용히 넘기지 않는다.
            if (s_font != null) return s_font;
            s_font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            if (s_font == null)
            {
                Debug.LogError("[TimerStopGameHud] Resources/Fonts/MalgunGothicLight 를 찾지 못했다 — 한글이 깨진다.");
                s_font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            }
            return s_font;
        }

        static Image MakeImage(Transform parent, string name, Color color)
        {
            var image = new GameObject(name, typeof(RectTransform), typeof(Image)).GetComponent<Image>();
            image.transform.SetParent(parent, false);
            image.color = color;
            return image;
        }

        static void Stretch(RectTransform rect)
        {
            rect.anchorMin = Vector2.zero;
            rect.anchorMax = Vector2.one;
            rect.offsetMin = rect.offsetMax = Vector2.zero;
        }

        static void AddOutline(GameObject go)
        {
            var outline = go.AddComponent<Outline>();
            outline.effectColor = Border;
            outline.effectDistance = new Vector2(1, -1);
        }

        static Text Label(Transform parent, string text, int size, Vector2 anchoredPos,
                          float height, FontStyle style, Color color, float width = 660f)
        {
            var label = new GameObject("Label", typeof(RectTransform), typeof(Text)).GetComponent<Text>();
            label.transform.SetParent(parent, false);
            label.font = Font();
            label.text = text;
            label.fontSize = size;
            label.fontStyle = style;
            label.color = color;
            label.alignment = TextAnchor.MiddleCenter;
            label.alignByGeometry = true;
            label.supportRichText = false;
            label.horizontalOverflow = HorizontalWrapMode.Overflow;
            label.verticalOverflow = VerticalWrapMode.Overflow;

            var rect = label.rectTransform;
            rect.anchorMin = rect.anchorMax = new Vector2(0.5f, 1f);
            rect.pivot = new Vector2(0.5f, 1f);
            rect.sizeDelta = new Vector2(width, height);
            rect.anchoredPosition = anchoredPos;
            return label;
        }

        static Button MakeButton(Transform parent, string text, Vector2 anchoredPos,
                                 Vector2 size, UnityEngine.Events.UnityAction onClick)
        {
            var image = MakeImage(parent, text, Card);
            var button = image.gameObject.AddComponent<Button>();
            AddOutline(image.gameObject);

            var rect = image.rectTransform;
            rect.anchorMin = rect.anchorMax = new Vector2(0.5f, 1f);
            rect.pivot = new Vector2(0.5f, 1f);
            rect.sizeDelta = size;
            rect.anchoredPosition = anchoredPos;

            var colors = button.colors;
            colors.highlightedColor = new Color(1.1f, 1.1f, 1.1f, 1f);
            colors.pressedColor = new Color(0.82f, 0.86f, 0.92f, 1f);
            colors.disabledColor = new Color(0.5f, 0.5f, 0.5f, 0.6f);
            colors.fadeDuration = 0.08f;
            button.colors = colors;

            var label = Label(image.transform, text, 24, Vector2.zero, size.y, FontStyle.Bold, TextMain);
            var lr = label.rectTransform;
            lr.anchorMin = Vector2.zero;
            lr.anchorMax = Vector2.one;
            lr.offsetMin = lr.offsetMax = Vector2.zero;

            button.onClick.AddListener(onClick);
            return button;
        }
    }
}
