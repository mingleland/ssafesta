using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// Mock 모드에서 하이 스트라이커 기록을 대신 받는 대역 (GitLab #233).
    ///
    /// <para><b>보상도 진행도도 지어내지 않는다.</b> 미션 판정은 전부 Spring 이 오늘 기록을 세어서 하므로
    /// (진행도 표 없음 — #233 ①), 여기서 그럴듯한 `playsToday` 를 만들어 돌려주면 에디터에서만 미션이
    /// 도는 것처럼 보인다. 그래서 개수만 세어 로그에 남기고 <c>simulated=true</c> 로 사실을 실어 보낸다
    /// (T-24: 조용한 대체 금지).</para>
    /// </summary>
    public sealed class MockHighStrikerClient : IHighStrikerClient
    {
        int _plays;
        int _best;

        /// <summary>Mock 은 실패하지 않는다.</summary>
        public string LastError => null;

        public Task<HighStrikerPlayAckDto> ReportPlayAsync(string machineId, int score)
        {
            _plays++;
            if (score > _best) _best = score;

            Debug.Log($"[MockHighStriker] 한 판 기록 — machineId={machineId} score={score} " +
                      $"(이 세션 {_plays}판, 최고 {_best}). Mock 이라 미션 진행도에는 반영되지 않는다.");

            return Task.FromResult(new HighStrikerPlayAckDto
            {
                accepted = true,
                score = score,
                playsToday = _plays,
                bestScoreToday = _best,
                simulated = true,
            });
        }
    }
}
