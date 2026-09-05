using System;
using Festa.Integration;
using ithappy.Casino;
using UnityEngine;

namespace Festa.Minigame.Slot
{
    /// <summary>
    /// slot machine 한 자리의 게임 상태 (S15P21A604-439). HUD 는 이것을 그리기만 한다.
    ///
    /// <para><b>판정은 서버(경계 <see cref="ISlotMachineClient"/>)가, 연출은 vendor
    /// <see cref="PresetUVSlotMachine"/> 이.</b> 이 클래스는 둘을 이어 주고 잔액을 보여 줄 뿐이다 —
    /// 결과를 만들지 않고, 벤더 스크립트의 공개 API(<c>SpinPresetByIndex</c>·<c>SpinRandom</c>·<c>IsSpinning</c>)만 쓴다.</para>
    ///
    /// <para>월드를 건드리지 않는다. 실패하거나 중간에 나가도 접속·플레이어 상태와 무관하다 (spec 014 FR-007 과 같은 원칙).</para>
    /// </summary>
    public sealed class SlotMachineSession
    {
        public const int Bet = 10;

        public enum Phase { Loading, Ready, Spinning, Result }

        public Phase Current { get; private set; } = Phase.Loading;

        /// <summary>표시 잔액. null = 알 수 없음(게스트·네트워크) — 그 사유는 <see cref="BalanceNote"/>.</summary>
        public int? Balance { get; private set; }
        public string BalanceNote { get; private set; }

        public SlotSpinResultDto Last { get; private set; }

        /// <summary>true 면 판정이 클라이언트 Mock 이다 — HUD 가 "체험판" 을 붙인다.</summary>
        public bool Simulated { get; private set; }

        /// <summary>사용자에게 띄울 안내(코인 부족 등). HUD 가 읽고 <see cref="DismissPopup"/> 로 지운다.</summary>
        public string Popup { get; private set; }

        public event Action Changed;

        readonly ISlotMachineClient _slot;
        readonly IWalletClient _wallet;
        readonly string _machineId;
        readonly PresetUVSlotMachine _reels;
        readonly int[] _winPresets;
        readonly int[] _losePresets;
        bool _disposed;

        public SlotMachineSession(ISlotMachineClient slot, IWalletClient wallet, string machineId,
                                  PresetUVSlotMachine reels, int[] winPresets, int[] losePresets)
        {
            _slot = slot;
            _wallet = wallet;
            _machineId = machineId;
            _reels = reels;
            _winPresets = winPresets ?? Array.Empty<int>();
            _losePresets = losePresets ?? Array.Empty<int>();
        }

        public bool IsBusy => Current == Phase.Loading || Current == Phase.Spinning;

        /// <summary>잔액을 읽어 온다. 실패하면 "모름" 으로 두고 사유를 남긴다 — 0 으로 꾸미지 않는다.</summary>
        public async void LoadBalance()
        {
            Current = Phase.Loading;
            Changed?.Invoke();

            var wallet = await _wallet.GetMyBalanceAsync();
            if (_disposed) return;

            if (wallet != null)
            {
                Balance = wallet.balance;
                BalanceNote = null;
                // 체험판(Mock 판정)이라도 시작 잔액은 실제 지갑과 맞춰 보여 준다.
                if (_slot is MockSlotMachineClient mock) mock.SeedBalance(wallet.balance);
            }
            else
            {
                Balance = null;
                BalanceNote = _wallet.LastError ?? "잔액을 알 수 없어요";
            }

            Current = Phase.Ready;
            Changed?.Invoke();
        }

        public void DismissPopup()
        {
            if (Popup == null) return;
            Popup = null;
            Changed?.Invoke();
        }

        /// <summary>10코인을 넣고 돌린다. 잔액이 모자라면 팝업만 띄운다.</summary>
        public async void Spin()
        {
            if (Current != Phase.Ready && Current != Phase.Result) return;

            if (Balance == null)
            {
                Popup = BalanceNote ?? "코인 정보를 가져오지 못했어요";
                Changed?.Invoke();
                return;
            }
            if (Balance.Value < Bet)
            {
                Popup = $"코인이 부족합니다 — 잔액 {Balance.Value}, 필요 {Bet}";
                Changed?.Invoke();
                return;
            }

            Current = Phase.Spinning;
            Last = null;
            Changed?.Invoke();

            var result = await _slot.SpinAsync(_machineId, Bet);
            if (_disposed) return;

            if (result == null || !result.accepted)
            {
                Popup = result?.error == "INSUFFICIENT_COIN"
                    ? $"코인이 부족합니다 — 잔액 {result.balanceAfter}, 필요 {Bet}"
                    : "판정을 받지 못했어요. 잠시 후 다시 시도해 주세요.";
                if (result != null) Balance = result.balanceAfter;
                Current = Phase.Ready;
                Changed?.Invoke();
                return;
            }

            Simulated = result.simulated;
            await PlayReels(result.tier);
            if (_disposed) return;

            Last = result;
            Balance = result.balanceAfter;
            Current = Phase.Result;
            Changed?.Invoke();
        }

        /// <summary>서버 등급에 맞는 프리셋으로 릴을 돌리고 멈출 때까지 기다린다. 릴이 없으면 연출 없이 통과.</summary>
        async Awaitable PlayReels(int tier)
        {
            if (_reels == null || !_reels.isActiveAndEnabled) return;

            // 유휴 연출(attract) 중이면 그것이 끝나기를 기다린다 — 벤더 스크립트는 회전 중 새 요청을 무시한다.
            while (_reels.IsSpinning) await Awaitable.NextFrameAsync();

            int index = PickPreset(tier);
            if (index < 0)
            {
                Debug.LogWarning("[SlotMachineSession] 프리셋 인덱스가 비어 있어 릴 연출을 건너뛴다.");
                return;
            }

            _reels.SpinPresetByIndex(index);
            await Awaitable.NextFrameAsync();
            while (_reels.IsSpinning) await Awaitable.NextFrameAsync();
        }

        int PickPreset(int tier)
        {
            if (tier <= 0)
                return _losePresets.Length > 0 ? _losePresets[UnityEngine.Random.Range(0, _losePresets.Length)] : -1;

            if (_winPresets.Length == 0) return -1;
            // tier 1..N → 프리셋 배열 앞에서부터 (약한 당첨 → 강한 당첨). 범위를 넘으면 가장 큰 당첨 연출.
            return _winPresets[Mathf.Clamp(tier - 1, 0, _winPresets.Length - 1)];
        }

        public void Dispose() => _disposed = true;
    }
}
