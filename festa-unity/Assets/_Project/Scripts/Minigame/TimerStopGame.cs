using System;
using Festa.Integration;
using UnityEngine;

namespace Festa.Minigame
{
    /// <summary>
    /// 타이머 정지 게임의 상태 기계 (spec 014 FR-001a~d).
    ///
    /// 서버가 발급한 목표 시간(5~10초)에 맞춰 사용자가 타이머를 멈춘다. 오차가 작을수록 좋다.
    ///
    /// ── UI 를 모른다 ──────────────────────────────────────────
    /// 이 클래스는 uGUI·IMGUI 어느 쪽도 참조하지 않는다. 상태와 이벤트만 내보내고
    /// 화면은 <see cref="TimerStopGameHud"/> 가 그린다. 그래야 규칙을 화면 없이 확인할 수 있고,
    /// 나중에 React 오버레이로 표면을 바꿔도 규칙은 그대로 남는다 (spec 014 T013).
    ///
    /// ── 월드와 분리 (FR-007) ──────────────────────────────────
    /// 게임은 네트워크·플레이어·씬 상태를 **읽지도 쓰지도 않는다.** 실패하든 중단하든
    /// 월드 접속에 영향이 없어야 하므로, 얽힐 수 있는 지점을 처음부터 두지 않았다.
    /// 입력 잠금 같은 월드 쪽 처리는 호출자가 판단한다.
    ///
    /// ── 시간은 unscaled 로 잰다 ───────────────────────────────
    /// <see cref="Time.timeScale"/> 로 재면 어딘가 한 곳이 0.5 를 넣는 순간 게임이 조용히
    /// 쉬워진다. 판정에 쓰는 값이라 스케일에 흔들리면 안 된다.
    /// </summary>
    public sealed class TimerStopGame
    {
        public const string GameId = "timer-stop";

        /// <summary>서버가 제한 시간을 안 주면 쓰는 잠정값. 진짜 값은 서버 몫이다 (FR-001d 기획 미결).</summary>
        public const float FallbackFailMargin = 3f;

        public enum Phase
        {
            /// <summary>아직 시작 전.</summary>
            Idle,
            /// <summary>서버에 세션·목표 시간을 요청하는 중.</summary>
            Starting,
            /// <summary>타이머가 흐르는 중 — 정지 입력을 받는다.</summary>
            Running,
            /// <summary>정지했거나 제한을 넘겼다. 결과 보고 중이거나 완료.</summary>
            Finished,
            /// <summary>시작하지 못했다. 사유는 <see cref="FailureReason"/>.</summary>
            Failed,
        }

        public Phase Current { get; private set; } = Phase.Idle;

        /// <summary>서버가 준 목표 시간(초). <see cref="Phase.Running"/> 이후 유효하다.</summary>
        public float TargetSeconds { get; private set; }

        /// <summary>시작 후 경과 시간(초).</summary>
        public float Elapsed { get; private set; }

        /// <summary>이 시각을 넘기면 실패로 끝난다 (FR-001d).</summary>
        public float FailAfterSeconds { get; private set; }

        /// <summary>정지 시각(초). 미정지면 -1.</summary>
        public float StoppedSeconds { get; private set; } = -1f;

        /// <summary>|목표 − 정지|. 미정지면 -1.</summary>
        public float ErrorSeconds { get; private set; } = -1f;

        /// <summary>제한 시간까지 정지하지 않아 실패로 끝났는지.</summary>
        public bool TimedOut { get; private set; }

        /// <summary>서버 판정. 보고 전이거나 실패면 null — **여기에만 보상 정보가 있다.**</summary>
        public GameResultAckDto Verdict { get; private set; }

        /// <summary>시작 실패 사유. 조용히 삼키지 않고 화면까지 올린다 (T-24).</summary>
        public string FailureReason { get; private set; }

        /// <summary>상태가 바뀔 때마다 알린다. HUD 가 이걸 듣고 다시 그린다.</summary>
        public event Action Changed;

        readonly IGameResultClient _client;

        public TimerStopGame(IGameResultClient client)
        {
            _client = client ?? throw new ArgumentNullException(nameof(client));
        }

        /// <summary>
        /// 서버에서 세션·목표 시간을 받아 타이머를 시작한다 (FR-001·001a).
        /// 이미 진행 중이면 아무 것도 하지 않는다 — 중복 시작은 세션을 낭비한다.
        /// </summary>
        public async void Start()
        {
            if (Current == Phase.Starting || Current == Phase.Running) return;

            Reset();
            Current = Phase.Starting;
            Changed?.Invoke();

            var session = await _client.StartAsync(GameId);

            if (session == null || string.IsNullOrEmpty(session.sessionId))
            {
                Current = Phase.Failed;
                // 사유가 있으면 그대로(게스트·로그인 만료·서버 오류) — "서버 응답 없음" 은 정말 사유를 모를 때만.
                FailureReason = _client.LastError ?? "게임을 시작하지 못했습니다 (서버 응답 없음)";
                Debug.LogError("[TimerStopGame] 세션 발급 실패 — 목표 시간을 임의로 만들지 않고 중단한다. " +
                               "클라이언트가 목표를 정하면 FR-008 이 깨진다.");
                Changed?.Invoke();
                return;
            }

            _session = session;
            TargetSeconds = session.targetSeconds;
            // 서버가 제한을 안 줬으면 잠정값. 서버 값이 오면 그쪽이 이긴다.
            FailAfterSeconds = session.failAfterSeconds > 0f
                ? session.failAfterSeconds
                : session.targetSeconds + FallbackFailMargin;

            Elapsed = 0f;
            Current = Phase.Running;
            Changed?.Invoke();
        }

        /// <summary>매 프레임 호출. 경과를 누적하고 제한 초과를 판정한다.</summary>
        public void Tick(float unscaledDeltaTime)
        {
            if (Current != Phase.Running) return;

            Elapsed += unscaledDeltaTime;
            if (Elapsed < FailAfterSeconds)
            {
                Changed?.Invoke();
                return;
            }

            // FR-001d — 제한을 넘겼다. 정지 입력이 없었으므로 실패로 끝낸다.
            TimedOut = true;
            StoppedSeconds = Elapsed;
            ErrorSeconds = Mathf.Abs(Elapsed - TargetSeconds);
            FinishAndReport();
        }

        /// <summary>정지 입력 (FR-001b). 흐르는 중이 아니면 무시한다.</summary>
        public void Stop()
        {
            if (Current != Phase.Running) return;

            TimedOut = false;
            StoppedSeconds = Elapsed;
            ErrorSeconds = Mathf.Abs(Elapsed - TargetSeconds);
            FinishAndReport();
        }

        /// <summary>
        /// 사용자가 그만두는 경우. **결과를 보고하지 않는다** —
        /// 미완료 게임의 처리는 기획 미결이라(C-05) 클라이언트가 정하지 않는다.
        /// </summary>
        public void Abort()
        {
            if (Current == Phase.Running || Current == Phase.Starting)
                Debug.Log("[TimerStopGame] 사용자가 중단했다 — 결과를 보고하지 않는다 (C-05 미결).");

            Reset();
            Current = Phase.Idle;
            Changed?.Invoke();
        }

        GameSessionDto _session;

        void Reset()
        {
            _session = null;
            TargetSeconds = 0f;
            Elapsed = 0f;
            FailAfterSeconds = 0f;
            StoppedSeconds = -1f;
            ErrorSeconds = -1f;
            TimedOut = false;
            Verdict = null;
            FailureReason = null;
        }

        async void FinishAndReport()
        {
            Current = Phase.Finished;
            Changed?.Invoke();   // 오차를 먼저 보여준다 — 서버 응답을 기다리게 하지 않는다 (FR-001c)

            var ack = await _client.ReportAsync(new GameResultDto
            {
                sessionId = _session.sessionId,
                gameId = GameId,
                targetSeconds = TargetSeconds,
                stoppedSeconds = StoppedSeconds,
                errorSeconds = ErrorSeconds,
                timedOut = TimedOut,
            });

            if (ack == null)
            {
                // 보상 여부를 **모르는** 상태다. "지급됨" 으로도 "미지급" 으로도 쓰면 안 된다.
                Debug.LogError("[TimerStopGame] 결과 보고 실패 — 보상 여부를 알 수 없다.");
                Verdict = new GameResultAckDto
                {
                    accepted = false,
                    message = "결과를 전송하지 못했습니다",
                };
            }
            else
            {
                Verdict = ack;
            }

            Changed?.Invoke();
        }
    }
}
