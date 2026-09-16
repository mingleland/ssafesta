package com.example.ssafesta.minigame;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slot machine settlement policy (spec 021, GitLab #205).
 *
 * <p><b>The odds table is configuration, and that was the decision.</b> GitLab #205 확정값 1 adopted
 * the table Unity's {@code MockSlotMachineClient} already runs — 낙첨 78 · ×2 15 · ×3 5 · ×5 2,
 * RTP 0.55 — and said the balance knobs live in yml so a later change is a configuration edit
 * rather than a code change. The defaults below are that table; nothing here invents a number.
 *
 * <p><b>낙첨 has no row.</b> It is whatever weight the tiers leave unclaimed, so the table cannot
 * be edited into one that sums to something other than 1.
 *
 * <p>{@link BigDecimal} weights rather than {@code double}, for the same reason
 * {@link MinigameProperties} uses them: the yml text <i>is</i> the policy, and a weight that reads
 * {@code 0.15} must not roll as {@code 0.15000000000000002}.
 *
 * @param betCoins   the only accepted bet. Fixed server-side and the request is merely checked
 *                   against it (헌법 16조) — Unity's {@code SlotMachineSession.Bet} is a constant 10
 *                   and the game part confirmed no variable betting is planned (#205 게임 파트 회신 2)
 * @param machineIds the machines a spin may be played on. Unknown ids are refused rather than
 *                   settled: a spin on a machine nobody placed would pile ledger entries under a
 *                   machine that does not exist
 * @param tiers      winning bands, <b>lowest multiplier first</b>; {@code tier = index + 1} and 0 is
 *                   낙첨
 */
@ConfigurationProperties("app.minigame.slot-machine")
public record SlotMachineProperties(int betCoins, List<String> machineIds, List<Tier> tiers) {

    /**
     * Unity clamps the tier it receives to 0..3 ({@code HttpSlotMachineClient.cs}) and
     * {@code SlotMachineInteractable} carries exactly three win presets. A fourth tier would be
     * paid in coins and then shown with the third tier's reel animation — right ledger, wrong
     * screen, no error anywhere (docs/26 응답 형태 행). Refuse the configuration instead.
     */
    static final int MAX_TIERS = 3;

    /** Weights are resolved to ten-thousandths, so 0.0001 is the finest odds this table expresses. */
    private static final int WEIGHT_SCALE = 4;

    static final int TOTAL_UNITS = 10_000;

    public SlotMachineProperties {
        if (betCoins < 1) {
            throw new IllegalStateException(
                    "app.minigame.slot-machine.bet-coins 는 1 이상이어야 합니다: " + betCoins);
        }
        if (machineIds == null || machineIds.isEmpty()) {
            throw new IllegalStateException("app.minigame.slot-machine.machine-ids 가 비어 있습니다 — "
                    + "화이트리스트가 비면 모든 스핀이 404 가 됩니다.");
        }
        for (String machineId : machineIds) {
            if (machineId == null || machineId.isBlank()) {
                throw new IllegalStateException("app.minigame.slot-machine.machine-ids 에 빈 값이 있습니다.");
            }
        }
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalStateException("app.minigame.slot-machine.tiers 가 비어 있습니다 — "
                    + "당첨 구간이 하나도 없으면 기계는 코인만 먹는다.");
        }
        if (tiers.size() > MAX_TIERS) {
            throw new IllegalStateException("app.minigame.slot-machine.tiers 는 " + MAX_TIERS
                    + "개까지입니다 — Unity 가 tier 를 0..3 으로 자르고 릴 프리셋도 3개다: " + tiers.size());
        }
        int units = 0;
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            if (tier == null) {
                throw new IllegalStateException("app.minigame.slot-machine.tiers[" + i + "] 가 비어 있습니다.");
            }
            units += tier.units();
            // 배수 오름차순이어야 tier 번호가 "약한 당첨 → 강한 당첨" 이다. Unity 는 tier 로 릴 프리셋을
            // 고르므로(SlotMachineSession.PickPreset), 거꾸로 적힌 표는 ×5 에 가장 약한 연출을 준다.
            if (i > 0 && tier.multiplier() <= tiers.get(i - 1).multiplier()) {
                throw new IllegalStateException("app.minigame.slot-machine.tiers 는 multiplier "
                        + "오름차순이어야 합니다 — [" + (i - 1) + "]=" + tiers.get(i - 1).multiplier()
                        + ", [" + i + "]=" + tier.multiplier());
            }
        }
        if (units > TOTAL_UNITS) {
            throw new IllegalStateException("app.minigame.slot-machine.tiers 의 weight 합이 1 을 넘습니다: "
                    + BigDecimal.valueOf(units, WEIGHT_SCALE));
        }
        machineIds = List.copyOf(machineIds);
        tiers = List.copyOf(tiers);
    }

    /**
     * The null check is not decoration: {@code machineIds} is a {@code List.copyOf} view and its
     * {@code contains(null)} throws rather than answering false, which would turn "no machine id"
     * into a 500 instead of the 404 it is.
     */
    public boolean knows(String machineId) {
        return machineId != null && machineIds.contains(machineId);
    }

    /**
     * Draws one outcome. {@code 0} is 낙첨 and carries whatever weight the tiers left over.
     *
     * <p>Drawn in whole ten-thousandths rather than from a float in [0,1): a cumulative comparison
     * against 0.78 + 0.15 + ... accumulates representation error, and the tier that absorbs it is
     * whichever one happens to sit at the boundary.
     */
    public int rollTier() {
        int roll = ThreadLocalRandom.current().nextInt(TOTAL_UNITS);
        int cumulative = 0;
        for (int i = 0; i < tiers.size(); i++) {
            cumulative += tiers.get(i).units();
            if (roll < cumulative) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Bet multiplier for a tier from {@link #rollTier}; tier 0 pays nothing. */
    public int multiplierOfTier(int tier) {
        if (tier < 1 || tier > tiers.size()) {
            return 0;
        }
        return tiers.get(tier - 1).multiplier();
    }

    /**
     * @param multiplier what the bet is multiplied by when this band hits — 2 or more, since ×1
     *                   would hand the bet back and read as a win on the reels
     * @param weight     probability of this band, 0 &lt; w &lt; 1, to four decimal places
     */
    public record Tier(int multiplier, BigDecimal weight) {

        public Tier {
            if (multiplier < 2) {
                throw new IllegalStateException(
                        "app.minigame.slot-machine.tiers[].multiplier 는 2 이상이어야 합니다: " + multiplier);
            }
            if (weight == null || weight.signum() <= 0 || weight.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalStateException(
                        "app.minigame.slot-machine.tiers[].weight 는 0 과 1 사이여야 합니다: " + weight);
            }
            if (weight.stripTrailingZeros().scale() > WEIGHT_SCALE) {
                throw new IllegalStateException("app.minigame.slot-machine.tiers[].weight 는 소수점 "
                        + WEIGHT_SCALE + "자리까지만 쓸 수 있습니다: " + weight);
            }
        }

        /** The weight in ten-thousandths — the unit {@link #rollTier} draws in. */
        int units() {
            return weight.movePointRight(WEIGHT_SCALE).intValueExact();
        }
    }
}
