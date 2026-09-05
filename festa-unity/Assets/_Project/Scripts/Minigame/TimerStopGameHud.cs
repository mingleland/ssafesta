using Festa.Integration;
using Festa.World.UI;
using TMPro;
using UnityEngine;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif
using UnityEngine.UI;

namespace Festa.Minigame
{
    /// <summary>
    /// 타이머 정지 게임 화면 (spec 014 T011·T012·T013). <see cref="FestaUiKit"/>(2D Game UI Kit) 로 그린다.
    ///
    /// <para>게임 로직은 <see cref="TimerStopGame"/> 에 있고 여기는 그리기와 입력만. 판정·보상은 서버 몫이라
    /// 화면은 "오차" 와 서버의 판정 문구만 보여 준다 (C-03 보상 정책 확정 전).</para>
    ///
    /// <para>월드 조작(이동·점프·F)은 열려 있는 동안 잠근다 — 열린 채 캐릭터가 뛰어다니던 사용자 지적(S15P21A604-437).</para>
    /// </summary>
    public sealed class TimerStopGameHud : MonoBehaviour
    {
        TimerStopGame _game;
        TMP_Text _target, _timer, _result, _verdict;
        Button _action;
        TMP_Text _actionLabel;

        /// <summary>게임 화면을 띄운다. 이미 떠 있으면 그것을 돌려준다.</summary>
        public static TimerStopGameHud Open(IGameResultClient client)
        {
            var existing = FindFirstObjectByType<TimerStopGameHud>();
            if (existing != null) return existing;

            var go = new GameObject("@TimerStopGameHud");
            var hud = go.AddComponent<TimerStopGameHud>();
            hud.Build(client);
            InputBridge.SetLocked(true);
            return hud;
        }

        bool _released;

        public void Close()
        {
            _game?.Abort();
            ReleaseLock();
            Destroy(gameObject);
        }

        void OnDestroy()
        {
            // 씬 전환 등으로 Close 없이 사라져도 잠금이 남지 않게. 단 **한 번만** — Close 뒤 프레임 끝의 OnDestroy 가
            // 다시 풀면 그 사이 다른 상호작용(게임기 초점 등)이 잡은 잠금까지 풀어 버린다(2026-09-06 실측).
            ReleaseLock();
        }

        void ReleaseLock()
        {
            if (_released) return;
            _released = true;
            InputBridge.SetLocked(false);
        }

        void Build(IGameResultClient client)
        {
            _game = new TimerStopGame(client);
            _game.Changed += Redraw;

            var canvas = FestaUiKit.OverlayCanvas(transform, "Canvas", 500);
            var root = canvas.transform;
            FestaUiKit.Backdrop(root);

            var panel = FestaUiKit.Panel(root, "Panel", UiSprite.PanelBlue);
            var pr = panel.rectTransform;
            FestaUiKit.Place(pr, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(0f, -10f), new Vector2(720f, 600f));

            FestaUiKit.TitleBanner(pr, "타이밍 스톱", new Vector2(0f, 30f), new Vector2(320f, 66f), 30f);
            FestaUiKit.IconButton(pr, UiSprite.IconClose, new Vector2(14f, 14f), 52f, Close);

            _target = FestaUiKit.Label(pr, "", 22f, new Vector2(0f, -78f), new Vector2(560f, 34f), FestaUiKit.Muted);

            // 타이머 — 어두운 표시창에 금색 숫자. 이 화면의 주인공.
            var display = FestaUiKit.Panel(pr, "Display", UiSprite.BarNavy);
            FestaUiKit.Place(display.rectTransform, new Vector2(0.5f, 1f), new Vector2(0.5f, 1f), new Vector2(0f, -120f), new Vector2(440f, 170f));
            _timer = FestaUiKit.Label(display.rectTransform, "0.00", 118f, Vector2.zero, Vector2.zero, FestaUiKit.Gold, FontStyles.Bold, outline: 0.18f);
            FestaUiKit.Stretch(_timer.rectTransform);

            _result = FestaUiKit.Title(pr, "", 32f, new Vector2(0f, -312f), new Vector2(600f, 46f));
            _verdict = FestaUiKit.Label(pr, "", 19f, new Vector2(0f, -360f), new Vector2(600f, 30f), FestaUiKit.Muted);

            _action = FestaUiKit.SpriteButton(pr, "시작", new Vector2(0f, -412f), new Vector2(300f, 78f), OnAction, UiSprite.ButtonOrange, 26f);
            _actionLabel = FestaUiKit.ButtonLabel(_action);

            FestaUiKit.Label(pr, "Space 로도 시작·정지  ·  Esc 로 나가기", 16f, new Vector2(0f, -520f), new Vector2(600f, 26f), FestaUiKit.Muted);

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
                    _timer.color = FestaUiKit.Gold;
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
                    _timer.color = FestaUiKit.Gold;
                    _result.text = "";
                    _verdict.text = "";
                    SetAction("정지", true);
                    break;

                case TimerStopGame.Phase.Finished:
                    _target.text = $"목표  {_game.TargetSeconds:F2}초";
                    _timer.text = _game.StoppedSeconds.ToString("F2");
                    if (_game.TimedOut)
                    {
                        _timer.color = FestaUiKit.Bad;
                        _result.text = "실패 — 제한 시간을 넘겼습니다";
                        _result.color = FestaUiKit.Bad;
                    }
                    else
                    {
                        // 오차만 보여준다. 등급·점수는 보상 정책이 정해진 뒤의 일이다 (C-03).
                        _timer.color = FestaUiKit.Good;
                        _result.text = $"오차  {_game.ErrorSeconds:F3}초";
                        _result.color = FestaUiKit.Text;
                    }
                    _verdict.text = _game.Verdict != null ? _game.Verdict.message : "결과 전송 중…";
                    SetAction("다시 하기", true);
                    break;

                case TimerStopGame.Phase.Failed:
                    _target.text = "";
                    _timer.text = "--";
                    _timer.color = FestaUiKit.Bad;
                    _result.text = _game.FailureReason;
                    _result.color = FestaUiKit.Bad;
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
    }
}
