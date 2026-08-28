using System;
using System.Threading.Tasks;

namespace Festa.Integration
{
    /// <summary>
    /// 미니게임 결과 보고 경계 (spec 014 T006·T008).
    ///
    /// **Unity 는 보상을 결정하지 않는다.** 진행하고 결과를 보고할 뿐이고,
    /// 지급 판단·일일 한도·원장 기록은 전부 서버 몫이다 (헌법 16조, spec 014 FR-003·006).
    /// 그래서 이 인터페이스에는 "지급하라" 가 없고 "이렇게 나왔다" 만 있다.
    ///
    /// ── 목표 시간을 서버가 발급하는 이유 ──────────────────────
    /// 클라이언트가 목표를 정하면 가장 쉬운 목표를 고를 수 있다. 서버가 발급하고
    /// 서버 시각으로 오차를 재면 조작 여지가 거의 없어진다 (plan §3-1, FR-008).
    /// 그래서 <see cref="StartAsync"/> 가 목표 시간을 **받아 온다** — 만들지 않는다.
    ///
    /// ── 멱등성 ────────────────────────────────────────────────
    /// <see cref="GameSessionDto.sessionId"/> 가 멱등성 키다 (T007, FR-004).
    /// 같은 세션의 결과가 두 번 도착해도 서버는 한 번만 지급해야 한다.
    /// 세션 식별자는 **시작할 때 서버가 발급**한다 — 클라이언트가 만들면
    /// 매번 새 키를 찍어 재전송과 중복 제출을 구분할 수 없다.
    /// </summary>
    public interface IGameResultClient
    {
        /// <summary>
        /// 게임 시작 — 서버가 세션과 목표 시간을 발급한다.
        /// 실패하면 null. 호출자는 게임을 시작하지 말고 사유를 표면화한다 (T-24: 조용한 폴백 금지).
        /// </summary>
        Task<GameSessionDto> StartAsync(string gameId);

        /// <summary>
        /// 결과 보고. 반환값은 **서버의 판정**이며 클라이언트의 주장이 아니다.
        /// 실패하면 null — 보상 여부를 알 수 없다는 뜻이므로 "지급됨" 으로 표시하면 안 된다.
        /// </summary>
        Task<GameResultAckDto> ReportAsync(GameResultDto result);
    }

    /// <summary>서버가 발급한 한 판. 목표 시간과 멱등성 키를 담는다.</summary>
    [Serializable]
    public class GameSessionDto
    {
        /// <summary>멱등성 키 (FR-004). 결과 보고 때 그대로 돌려준다.</summary>
        public string sessionId;

        /// <summary>이번 판의 목표 시간(초). spec 014 FR-001a — 5~10초 사이.</summary>
        public float targetSeconds;

        /// <summary>
        /// 이 시각을 넘기면 실패로 끝낸다 (FR-001d "크게 초과").
        /// **서버가 정하는 값이다** — 몇 초를 "크게" 로 볼지는 기획 결정이라
        /// 클라이언트가 상수로 박으면 안 된다. 0 이하면 클라이언트 잠정값을 쓴다.
        /// </summary>
        public float failAfterSeconds;
    }

    /// <summary>한 판의 결과. 서버가 검증할 수 있도록 판정 근거를 그대로 싣는다.</summary>
    [Serializable]
    public class GameResultDto
    {
        public string sessionId;
        public string gameId;

        /// <summary>서버가 발급했던 목표 시간. 서버가 자기 발급값과 대조한다.</summary>
        public float targetSeconds;

        /// <summary>사용자가 정지시킨 시각(초). 시작 시점 기준 경과.</summary>
        public float stoppedSeconds;

        /// <summary>|목표 − 정지|. 서버가 재계산해 검증할 수 있는 파생값이다.</summary>
        public float errorSeconds;

        /// <summary>제한 시간까지 정지하지 않아 실패로 끝났는지 (FR-001d).</summary>
        public bool timedOut;
    }

    /// <summary>
    /// 서버 판정. **보상 여부는 여기에만 있다** — 클라이언트가 계산하지 않는다.
    /// </summary>
    [Serializable]
    public class GameResultAckDto
    {
        /// <summary>서버가 결과를 받아들였는지. false 면 검증 실패(FR-008)일 수 있다.</summary>
        public bool accepted;

        /// <summary>지급된 코인. 0 이면 무보상 — 사유는 <see cref="message"/> 에 있다.</summary>
        public int rewardedCoins;

        /// <summary>일일 한도에 걸려 미지급인 경우 true (FR-005, SC-003).</summary>
        public bool dailyLimitReached;

        /// <summary>사용자에게 그대로 보여줄 수 있는 안내. 비어 있을 수 있다.</summary>
        public string message;
    }
}
