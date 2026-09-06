using System;
using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 서버 없는 slot machine 판정 (S15P21A604-439). BE 계약이 확정되면 Http 구현으로 바꾸고 이 클래스는 Mock 모드에만 남긴다.
    ///
    /// 확률은 "실제 slot machine 수준" 요구를 따라 RTP ≈ 89% 로 잡았다 —
    /// 낙첨 64% · ×2 25% · ×3 8% · ×5 3% → 기대 환급 8.9/10.
    /// 잔액은 이 인스턴스 안에서만 흐른다(원장 미반영). 생성 시 시드 잔액을 받는다.
    /// </summary>
    public sealed class MockSlotMachineClient : ISlotMachineClient
    {
        public const int DefaultSeedBalance = 100;

        // tier: 0 낙첨, 1 ×2, 2 ×3, 3 ×5
        static readonly (int tier, int multiplier, float weight)[] Table =
        {
            (0, 0, 0.64f),
            (1, 2, 0.25f),
            (2, 3, 0.08f),
            (3, 5, 0.03f),
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
