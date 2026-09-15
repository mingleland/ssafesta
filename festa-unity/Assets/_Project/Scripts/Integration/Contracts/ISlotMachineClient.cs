using System;
using System.Threading.Tasks;

namespace Festa.Integration
{
    /// <summary>
    /// 광장 slot machine 결과 경계 (S15P21A604-439).
    ///
    /// <b>Unity 는 결과를 정하지 않는다.</b> 베팅을 보내고 서버 판정(당첨 등급·지급액·잔액)을 받아
    /// 릴 연출만 맞춘다 — spec 014 의 "지급 판단·원장은 서버" 원칙 그대로다.
    ///
    /// BE 엔드포인트는 아직 없다(docs/26 ③ 제안: <c>POST /api/v1/minigames/slot-machines/{machineId}/spins</c>).
    /// 그때까지 <see cref="MockSlotMachineClient"/> 가 같은 모양으로 동작하고, 결과 DTO 의
    /// <see cref="SlotSpinResultDto.simulated"/> 가 true 라 HUD 가 "체험판" 임을 드러낸다 — 조용히 실서버인 척하지 않는다.
    /// </summary>
    public interface ISlotMachineClient
    {
        /// <summary>한 판. 실패(잔액 부족·네트워크)는 null 이 아니라 <see cref="SlotSpinResultDto.accepted"/> false 로 돌아온다.</summary>
        Task<SlotSpinResultDto> SpinAsync(string machineId, int bet);
    }

    [Serializable]
    public class SlotSpinResultDto
    {
        /// <summary>서버가 받아들였는가. false 면 <see cref="error"/> 에 사유(INSUFFICIENT_COIN 등).</summary>
        public bool accepted;
        public string error;

        public string sessionId;
        public int bet;

        /// <summary>지급 코인. 0 = 낙첨. 최대 50 (베팅 10 기준 ×5).</summary>
        public int payout;

        /// <summary>당첨 등급. 0 낙첨 · 1 ×2 · 2 ×3 · 3 ×5. 릴 연출 프리셋 선택에만 쓴다.</summary>
        public int tier;

        /// <summary>판 뒤 잔액. 서버 원장 기준 — 실서버 전에는 표시용 추정치.</summary>
        public int balanceAfter;

        /// <summary>true 면 서버 판정이 아니라 클라이언트 Mock 이다. HUD 가 반드시 표시한다.</summary>
        public bool simulated;
    }

    /// <summary>코인 잔액 조회 경계 — <c>GET /api/v1/wallets/me</c> (spec 003).</summary>
    public interface IWalletClient
    {
        /// <summary>실패(게스트 403·네트워크)면 null. 호출자는 "잔액 모름" 으로 다룬다 — 0 으로 꾸미지 않는다.</summary>
        Task<WalletBalanceDto> GetMyBalanceAsync();

        /// <summary>마지막 실패 사유(사람이 읽는 문구). 성공 뒤에는 null.</summary>
        string LastError { get; }
    }

    [Serializable]
    public class WalletBalanceDto
    {
        public long userId;
        public int balance;
        public string updatedAt;
    }
}
