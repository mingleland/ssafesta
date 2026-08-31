using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 미니게임 결과 보고의 Mock (spec 014 T006).
    ///
    /// ⚠ **이건 임시 대역이다. 검증을 대신하지 않는다.**
    /// 목표 시간을 서버가 발급해야 조작을 막을 수 있는데(plan §3-1, FR-008),
    /// Mock 은 클라이언트 프로세스 안에서 만든다 — 즉 **지금 상태로는 FR-008 이 성립하지 않는다.**
    /// 실서버(spec 003 wallet-coin) 가 붙으면 이 클래스는 통째로 교체된다.
    /// 그 사실을 숨기지 않으려고 시작할 때마다 경고를 남긴다 (T-24: 조용한 대체 금지).
    ///
    /// 보상은 **판단하지 않는다.** 보상 정책·일일 한도는 미결이고(C-03·C-04) 서버 몫이라,
    /// 여기서 그럴듯한 숫자를 지어내면 나중에 "이미 되던 것" 으로 오해된다 (헌법 30조).
    /// 따라서 항상 `rewardedCoins = 0` 에 사유를 붙여 돌려준다.
    /// </summary>
    public sealed class MockGameResultClient : IGameResultClient
    {
        // spec 014 FR-001a — 목표는 5~10초 사이.
        const float MinTarget = 5f;
        const float MaxTarget = 10f;

        // FR-001d "크게 초과" 의 잠정값. 진짜 값은 서버가 내려준다 (기획 미결).
        const float FailMargin = 3f;

        // 멱등성 확인용 — 같은 세션이 두 번 오면 두 번째는 중복으로 표시한다 (FR-004).
        // 실제 중복 방지는 서버가 한다. Mock 은 클라이언트 재전송 버그를 잡아 주는 정도다.
        readonly HashSet<string> _reported = new();

        public Task<GameSessionDto> StartAsync(string gameId)
        {
            var target = UnityEngine.Random.Range(MinTarget, MaxTarget);
            var session = new GameSessionDto
            {
                // Mock 이므로 클라이언트가 만든다. 실서버에서는 서버 발급이다.
                sessionId = Guid.NewGuid().ToString("N"),
                targetSeconds = target,
                failAfterSeconds = target + FailMargin,
            };

            Debug.LogWarning(
                $"[MockGameResult] 목표 시간을 **클라이언트가** 만들었다 — {target:F2}s. " +
                "실서버 연동 전까지 FR-008(점수 조작 방지)은 성립하지 않는다 (spec 014).");

            return Task.FromResult(session);
        }

        public Task<GameResultAckDto> ReportAsync(GameResultDto result)
        {
            if (result == null || string.IsNullOrEmpty(result.sessionId))
            {
                Debug.LogError("[MockGameResult] sessionId 없는 결과는 보고할 수 없다 — 멱등성 키가 없다.");
                return Task.FromResult<GameResultAckDto>(null);
            }

            bool duplicate = !_reported.Add(result.sessionId);
            if (duplicate)
            {
                Debug.LogWarning($"[MockGameResult] 같은 세션이 다시 보고됐다 (sessionId={result.sessionId}). " +
                                 "실서버라면 중복 지급 없이 최초 결과를 돌려줘야 한다 (FR-004).");
            }

            Debug.Log($"[MockGameResult] 결과 보고 — 목표 {result.targetSeconds:F2}s / " +
                      $"정지 {result.stoppedSeconds:F2}s / 오차 {result.errorSeconds:F3}s / " +
                      $"timeout={result.timedOut}");

            return Task.FromResult(new GameResultAckDto
            {
                accepted = true,
                rewardedCoins = 0,
                dailyLimitReached = false,
                // 보상 정책이 정해지지 않았다는 사실을 사용자 화면까지 그대로 올린다.
                message = "기록되었습니다 (보상 연동 전)",
            });
        }
    }
}
