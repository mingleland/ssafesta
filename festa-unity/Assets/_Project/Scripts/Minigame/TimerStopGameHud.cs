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
        /// <summary>입력 잠금 주인 이름. 호스트 Overlay 가 자기 잠금을 풀 때 이 화면 것까지 풀지 않게 한다.</summary>
        const string LockOwner = "TimerStopGameHud";

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
            InputBridge.SetLocked(true, LockOwner);
            // 호스트에 "Unity 미니게임 모달 열림" 을 알린다(#132). ESC 중재용 상태이고, 닫기는 RequestExitWorldUi 로만 온다.
            WorldUiBridge.MinigameOpen = () => FindFirstObjectByType<TimerStopGameHud>() != null;
            WorldUiBridge.CloseMinigame = () => { var h = FindFirstObjectByType<TimerStopGameHud>(); if (h != null) h.Close(); };
            WorldUiBridge.Publish();
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
            InputBridge.SetLocked(false, LockOwner);
            // 이 HUD 는 곧 파괴된다 — MinigameOpen 은 다음 프레임에 false 가 되지만 상태 push 는 지금 한다.
            WorldUiBridge.MinigameOpen = null;
            WorldUiBridge.CloseMinigame = null;
            WorldUiBridge.Publish();
        }

        void Build(IGameResultClient client)
        {
            _game = new TimerStopGame(client);
            _game.Changed += Redraw;

            var canvas = FestaUiKit.OverlayCanvas(transform, "Canvas", 500);
            var root = canvas.transform;
            FestaUiKit.Backdrop(root);

            var panel = FestaUiKit.Panel(root, "Card");
            var pr = panel.rectTransform;
            FestaUiKit.Place(pr, new Vector2(0.5f, 0.5f), new Vector2(0.5f, 0.5f), new Vector2(0f, -10f), new Vector2(640f, 560f));

            FestaUiKit.TitleBanner(pr, "타이밍 스톱", new Vector2(0f, 22f), new Vector2(220f, 46f), 22f);
            FestaUiKit.CloseButton(pr, new Vector2(-14f, -14f), 40f, Close);

            _target = FestaUiKit.Label(pr, "", 19f, new Vector2(0f, -66f), new Vector2(520f, 30f), FestaUiKit.Muted);

            // 타이머 — 어두운 표시창에 금색 숫자. 이 화면의 주인공.
            var display = FestaUiKit.Panel(pr, "Display", FestaUiKit.Card.Charcoal, 22);
            FestaUiKit.Place(display.rectTransform, new Vector2(0.5f, 1f), new Vector2(0.5f, 1f), new Vector2(0f, -108f), new Vector2(420f, 160f));
            _timer = FestaUiKit.Label(display.rectTransform, "0.00", 110f, Vector2.zero, Vector2.zero, FestaUiKit.Gold, FontStyles.Bold);
            FestaUiKit.Stretch(_timer.rectTransform);

            _result = FestaUiKit.Title(pr, "", 28f, new Vector2(0f, -292f), new Vector2(560f, 44f));
            _verdict = FestaUiKit.Label(pr, "", 17f, new Vector2(0f, -338f), new Vector2(560f, 28f), FestaUiKit.Muted);

            _action = FestaUiKit.PillButton(pr, "시작", new Vector2(0f, -392f), new Vector2(280f, 66f), OnAction, true, 24f);
            _actionLabel = FestaUiKit.ButtonLabel(_action);

            FestaUiKit.Label(pr, "Space  시작·정지   ·   Esc  나가기", 14f, new Vector2(0f, -496f), new Vector2(560f, 24f), FestaUiKit.Muted);

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
                        // 오차는 **서버 판정값**을 우선 그린다(GitLab #134 §2) — 클라이언트 계산값과 갈리는 날 사용자에게
                        // 클라이언트 숫자가 보이면 안 된다. 서버 응답 전·체험판이면 클라이언트 값.
                        _timer.color = FestaUiKit.Good;
                        bool serverVerdict = _game.Verdict != null && !_game.Verdict.simulated && _game.Verdict.accepted;
                        float shownError = serverVerdict ? _game.Verdict.errorSeconds : _game.ErrorSeconds;
                        _result.text = $"오차  {shownError:F3}초";
                        _result.color = FestaUiKit.Text;
                    }
                    // 체험판(Mock 판정)이면 그 사실을 반드시 드러낸다 — 슬롯머신과 같은 규칙이다.
                    // 조용히 실서버인 척하면 사용자는 기록·보상이 남은 줄 안다 (T-24).
                    // 실서버 판정은 구조 필드로 문구를 만든다 — 서버 message 는 ASCII 영문(WebGL IMGUI 한글 문제, T-22)이라
                    // 그대로 보이면 어색하다(#134 §6). 한도 안내는 spec 014 Acceptance Scenario 4.
                    _verdict.text = _game.Verdict == null ? "결과 전송 중…"
                                  : _game.Verdict.simulated ? "체험판 — 기록·보상이 남지 않습니다"
                                  : !_game.Verdict.accepted ? "결과가 인정되지 않았습니다 — 다시 시도해 주세요"
                                  : _game.Verdict.rewardedCoins > 0
                                      ? $"+{_game.Verdict.rewardedCoins} 코인" + (_game.Verdict.dailyLimitReached ? " · 오늘 보상 한도에 도달했습니다" : $" · 오늘 남은 보상 {_game.Verdict.dailyRemainingCoins}")
                                  : _game.Verdict.dailyLimitReached ? "오늘 보상 한도에 도달했습니다 — 게임은 계속할 수 있어요"
                                  : "보상 구간 밖 — 목표에 더 가깝게 멈춰 보세요";
                    _verdict.color = _game.Verdict != null && _game.Verdict.simulated
                                   ? FestaUiKit.Accent : FestaUiKit.Text;
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
