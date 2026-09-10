using System;
using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 서버 없는 slot machine 판정 (S15P21A604-439). BE 계약이 확정되면 Http 구현으로 바꾸고 이 클래스는 Mock 모드에만 남긴다.
    ///
    /// <para><b>확률은 코인이 실물로 교환된다는 전제로 잡는다</b> (사용자 지시 2026-09-10 — 코인을 간식으로
    /// 바꿔 줄 계획). 처음에는 "실제 slot machine 수준" 요구로 RTP 89%(낙첨 64% · ×2 25% · ×3 8% · ×5 3%)
    /// 였는데, 세 판에 한 번 이상 터져 "쉽게 버는 기계" 로 읽혔다.</para>
    ///
    /// <para>지금은 <b>낙첨 78% · ×2 15% · ×3 5% · ×5 2%</b> → 당첨률 22%, RTP 0.55
    /// (= 0.15×2 + 0.05×3 + 0.02×5). 한 판 10 코인 기준 기대 손실 4.5 코인이라 <b>확실한 코인 소각처</b>다.</para>
    ///
    /// <para><b>다만 교환 부담을 정하는 것은 이 표가 아니다.</b> RTP 가 100% 미만인 한 슬롯머신은 코인을
    /// 만들지 못하고 태우기만 한다 — 실물로 나갈 총량은 <b>코인 유입</b>(신규 200 · 일일 50 · 미니게임 보상)이
    /// 정한다. 여기서 낮추는 것은 "한 판에 크게 따는 느낌" 과 분산이고, 총 지출 상한은 지급 정책에서 잡아야 한다.</para>
    ///
    /// <para>잔액은 이 인스턴스 안에서만 흐른다(원장 미반영). 생성 시 시드 잔액을 받는다.</para>
    /// </summary>
    public sealed class MockSlotMachineClient : ISlotMachineClient
    {
        public const int DefaultSeedBalance = 100;

        // tier: 0 낙첨, 1 ×2, 2 ×3, 3 ×5 — 합 1.00, RTP 0.55
        static readonly (int tier, int multiplier, float weight)[] Table =
        {
            (0, 0, 0.78f),
            (1, 2, 0.15f),
            (2, 3, 0.05f),
            (3, 5, 0.02f),
        };

        int _balance;

        public MockSlotMachineClient(int seedBalance = DefaultSeedBalance)
        {
            _balance = seedBalance;
        }

        /// <summary>실서버 잔액을 알게 됐을 때 표시 잔액을 맞춘다(체험판에서만 의미).</summary>
        public void SeedBalance(int balance) => _balance = balance;

        public int Balance => _balance;

        public Task<SlotSpinResultDto> SpinAsync(string machineId, int bet)
        {
            if (bet <= 0)
                return Task.FromResult(new SlotSpinResultDto { accepted = false, error = "INVALID_BET", bet = bet, balanceAfter = _balance, simulated = true });

            if (_balance < bet)
                return Task.FromResult(new SlotSpinResultDto { accepted = false, error = "INSUFFICIENT_COIN", bet = bet, balanceAfter = _balance, simulated = true });

            _balance -= bet;
            var roll = UnityEngine.Random.value;
            float acc = 0f;
            int tier = 0, mult = 0;
            foreach (var row in Table)
            {
                acc += row.weight;
                if (roll <= acc) { tier = row.tier; mult = row.multiplier; break; }
            }
            int payout = bet * mult;
            _balance += payout;

            Debug.LogWarning($"[MockSlotMachine] 판정을 **클라이언트가** 만들었다 — machine={machineId} bet={bet} tier={tier} payout={payout} balance={_balance}. " +
                             "BE 엔드포인트 확정 전까지 코인 원장에는 반영되지 않는다 (S15P21A604-439, docs/26 ③).");

            return Task.FromResult(new SlotSpinResultDto
            {
                accepted = true,
                sessionId = Guid.NewGuid().ToString("N"),
                bet = bet,
                payout = payout,
                tier = tier,
                balanceAfter = _balance,
                simulated = true,
            });
        }
    }

    /// <summary>Mock 모드의 지갑 — 잔액 100 고정.</summary>
    public sealed class MockWalletClient : IWalletClient
    {
        public string LastError => null;

        public Task<WalletBalanceDto> GetMyBalanceAsync()
            => Task.FromResult(new WalletBalanceDto { userId = 0, balance = MockSlotMachineClient.DefaultSeedBalance, updatedAt = "" });
    }
}
