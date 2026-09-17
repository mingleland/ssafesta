using System;
using System.Threading.Tasks;

namespace Festa.Integration
{
    /// <summary>
    /// 하이 스트라이커 한 판을 Spring 에 남기는 경계 — <b>일일 미션 `STRIKER_PLAY_3`·`STRIKER_SCORE` 의 유일한 근거</b>
    /// (GitLab #233).
    ///
    /// <para><b>왜 필요한가.</b> 미션 계약은 3·4 번의 근거를 `minigame_sessions` 로 적었는데, 그 표를 채우는 것은
    /// 타이밍 스톱뿐이다(<see cref="IGameResultClient"/> → <c>/minigames/timer-stop/…</c>). 그런데 정작 사용자가
    /// 월드에서 치는 하이 스트라이커는 Netcode 전용이라 — 점수를 데디케이티드 서버의 정적 표에서 굴리고 RPC 로 뿌린 뒤
    /// 버린다 — Spring 에 아무 기록도 남기지 않는다. 즉 이 경계가 없으면 미션 3·4 는 BE 가 무엇을 구현하든
    /// <b>영구 미완성</b>이다. 타이밍 스톱 화면은 FE 이관(#166) 뒤 아직 어디에도 없어 대체 근거가 되지 못한다.</para>
    ///
    /// <para><b>보상을 요청하지 않는다.</b> 이 호출은 "이렇게 나왔다" 만 남긴다 — 코인을 줄지는 미션 수령
    /// (<c>POST /missions/daily/{{missionId}}/claims</c>) 에서 서버가 정한다. 헌법 1·16조 그대로이고,
    /// 그래서 이 인터페이스에는 보상 필드가 없다.</para>
    ///
    /// <para><b>점수는 클라이언트가 신고한다 — 위조 가능하다.</b> 표시 권위는 Netcode 서버에 그대로 두어
    /// (사람마다 다른 점수가 보이면 안 된다) 축제 직전에 검증된 연출 경로를 건드리지 않았다. 대신 BE 가
    /// 유효 점수 범위로 clamp 하고 기계 쿨다운(3.2초) 만큼 요청을 제한하면 최악이 "작정한 사람이 15 코인을 먼저 받는다"
    /// 로 묶인다 — 교내 축제 규모에서 감수할 수 있는 교환이고, #233 에 그대로 적어 통보한다.</para>
    /// </summary>
    public interface IHighStrikerClient
    {
        /// <summary>
        /// 한 판을 기록한다. <b>실패해도 게임 진행을 막지 않는다</b> — 연출은 이미 끝났고 여기서 막으면
        /// 기록용 호출이 게임을 세우는 셈이 된다. 호출자는 결과를 버려도 되지만, 사유는 로그에 남는다.
        /// </summary>
        Task<HighStrikerPlayAckDto> ReportPlayAsync(string machineId, int score);

        /// <summary>마지막 실패의 사유 코드. 성공 뒤에는 null.</summary>
        string LastError { get; }
    }

    /// <summary>서버가 받아들인 한 판. 진행도 표시는 FE 패널이 하므로 여기 값은 진단·로그용이다.</summary>
    [Serializable]
    public class HighStrikerPlayAckDto
    {
        /// <summary>서버가 기록했는가. false 면 <see cref="error"/> 에 사유.</summary>
        public bool accepted;

        /// <summary>실패 사유 코드. <c>ENDPOINT_MISSING</c>·<c>MEMBER_ONLY</c>·<c>UNAUTHORIZED</c>·<c>NETWORK</c> 등.</summary>
        public string error;

        /// <summary>서버가 부여한 기록 식별자. 재전송 판정에 쓸 수 있게 받아 둔다.</summary>
        public string playId;

        /// <summary>서버가 받아들인 점수(=clamp 된 값). 신고값과 다를 수 있다.</summary>
        public int score;

        /// <summary>오늘 누적 판 수. 서버가 내려보내지 않으면 -1.</summary>
        public int playsToday = -1;

        /// <summary>오늘 최고 점수. 서버가 내려보내지 않으면 -1.</summary>
        public int bestScoreToday = -1;

        /// <summary>true 면 서버가 아니라 Mock 이다 — 미션 진행도에 반영되지 않는다.</summary>
        public bool simulated;
    }
}
