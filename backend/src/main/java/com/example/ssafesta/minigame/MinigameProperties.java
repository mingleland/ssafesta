package com.example.ssafesta.minigame;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Minigame settlement policy (spec 014 C-03, C-04).
 *
 * <p>These are configuration because the lead's own decision said so: C-03 confirmed 오차 구간별
 * 차등 보상 and then that "구체 수치는 경제 밸런스로 조정 가능하게 <b>데이터로</b> 둔다". The values below
 * are defaults that implement that decision, not a policy this code invented — when 기획 fixes the
 * numbers it is one yml edit, no code change (헌법 30조).
 *
 * <p><b>There is no time zone here.</b> {@code app.wallet.daily-grant-zone} already means "which
 * calendar day a coin policy belongs to"; a second copy is a second source of truth, and the day
 * the two disagree the daily grant and the minigame cap run on different calendars.
 *
 * <p>{@link BigDecimal} rather than {@code double} throughout, so the yml text <i>is</i> the policy
 * and no {@code 0.30000000000000004} ever reaches a comparison against a ±0.1s band.
 *
 * @param dailyCapCoins the most a member can earn from minigames in one day (C-04 — 50)
 * @param timerStop     the one minigame spec 014 FR-009 allows
 */
@ConfigurationProperties("app.minigame")
public record MinigameProperties(int dailyCapCoins, TimerStop timerStop) {

    public MinigameProperties {
        if (dailyCapCoins < 1) {
            throw new IllegalStateException(
                    "app.minigame.daily-cap-coins 는 1 이상이어야 합니다: " + dailyCapCoins);
        }
        if (timerStop == null) {
            throw new IllegalStateException("app.minigame.timer-stop 설정이 없습니다.");
        }
    }

    /**
     * Timing-stop settings.
     *
     * @param targetMinSeconds       lower bound of the random target (FR-001a — 5s)
     * @param targetMaxSeconds       upper bound, inclusive (FR-001a — 10s)
     * @param failMarginSeconds      {@code failAfterSeconds = target + this} (FR-001d). The number
     *                               itself is still 리드 미확정 — 게임 파트가 GitLab #134 에서 목표+3초에
     *                               이견 없음을 회신했고 그 값을 기본값으로 둔다
     * @param elapsedToleranceSeconds how far the reported stop time may sit from the server's own
     *                               elapsed time before the claim is refused. Two-sided: above it
     *                               means "I stopped at a time that has not happened yet", below it
     *                               means "I sat on the answer". 20× the tightest reward band, so a
     *                               real player on a bad connection never trips it
     * @param tiers                  reward bands, <b>widest first</b>; {@code tier = index + 1} and
     *                               0 means no reward
     */
    public record TimerStop(BigDecimal targetMinSeconds, BigDecimal targetMaxSeconds,
                            BigDecimal failMarginSeconds, BigDecimal elapsedToleranceSeconds,
                            List<Tier> tiers) {

        /** {@code NUMERIC(8,3)} holds milliseconds and no finer, so the configuration may not either. */
        private static final int MAX_SCALE = 3;

        public TimerStop {
            requirePositive("target-min-seconds", targetMinSeconds);
            requirePositive("target-max-seconds", targetMaxSeconds);
            requirePositive("fail-margin-seconds", failMarginSeconds);
            requirePositive("elapsed-tolerance-seconds", elapsedToleranceSeconds);
            requireMillisecondScale("target-min-seconds", targetMinSeconds);
            requireMillisecondScale("target-max-seconds", targetMaxSeconds);
            if (targetMinSeconds.compareTo(targetMaxSeconds) >= 0) {
                throw new IllegalStateException("app.minigame.timer-stop.target-min-seconds 는 "
                        + "target-max-seconds 보다 작아야 합니다: " + targetMinSeconds + " >= " + targetMaxSeconds);
            }
            if (tiers == null || tiers.isEmpty()) {
                throw new IllegalStateException("app.minigame.timer-stop.tiers 가 비어 있습니다 — "
                        + "보상 구간이 하나도 없으면 어떤 판도 보상을 받지 못합니다.");
            }
            // 넓은 것부터 정렬돼 있어야 tier 번호가 안정적이고, tierOf 의 역순 탐색이 성립한다.
            // 거꾸로 적힌 표는 완벽한 정지에 가장 낮은 보상을 주고 일주일 아무도 모른다.
            for (int i = 0; i < tiers.size(); i++) {
                Tier tier = tiers.get(i);
                if (tier == null) {
                    throw new IllegalStateException("app.minigame.timer-stop.tiers[" + i + "] 가 비어 있습니다.");
                }
                if (i == 0) {
                    continue;
                }
                Tier previous = tiers.get(i - 1);
                if (tier.maxErrorSeconds().compareTo(previous.maxErrorSeconds()) >= 0) {
                    throw new IllegalStateException("app.minigame.timer-stop.tiers 는 max-error-seconds "
                            + "내림차순이어야 합니다 — [" + (i - 1) + "]=" + previous.maxErrorSeconds()
                            + ", [" + i + "]=" + tier.maxErrorSeconds());
                }
                if (tier.coins() <= previous.coins()) {
                    throw new IllegalStateException("app.minigame.timer-stop.tiers 는 coins 오름차순이어야 "
                            + "합니다 — 더 좁은 구간이 더 적게 주면 정밀할수록 손해다: [" + (i - 1) + "]="
                            + previous.coins() + ", [" + i + "]=" + tier.coins());
                }
            }
            tiers = List.copyOf(tiers);
        }

        /** {@code failAfterSeconds} for a given target — the client stops the game at this point. */
        public BigDecimal failAfterSeconds(BigDecimal targetSeconds) {
            return targetSeconds.add(failMarginSeconds);
        }

        /**
         * The <b>narrowest</b> matching band, or 0 for no reward.
         *
         * <p>Scanned in reverse because the table is ordered widest-first: taking the first forward
         * match would hand a 0.05s stop the 0.5s band's reward. Bounds are inclusive, so an error of
         * exactly 0.100 earns the 0.1s band.
         */
        public int tierOf(BigDecimal errorSeconds) {
            for (int i = tiers.size() - 1; i >= 0; i--) {
                if (errorSeconds.compareTo(tiers.get(i).maxErrorSeconds()) <= 0) {
                    return i + 1;
                }
            }
            return 0;
        }

        /** Coins for a tier from {@link #tierOf}; tier 0 pays nothing. */
        public int coinsOfTier(int tier) {
            if (tier < 1 || tier > tiers.size()) {
                return 0;
            }
            return tiers.get(tier - 1).coins();
        }

        private static void requirePositive(String key, BigDecimal value) {
            if (value == null || value.signum() <= 0) {
                throw new IllegalStateException(
                        "app.minigame.timer-stop." + key + " 는 0보다 커야 합니다: " + value);
            }
        }

        private static void requireMillisecondScale(String key, BigDecimal value) {
            if (value.stripTrailingZeros().scale() > MAX_SCALE) {
                throw new IllegalStateException("app.minigame.timer-stop." + key + " 는 밀리초(소수점 3자리)"
                        + "까지만 쓸 수 있습니다 — 목표 시간 컬럼이 NUMERIC(8,3) 입니다: " + value);
            }
        }
    }

    /**
     * @param maxErrorSeconds inclusive upper bound of |목표 − 정지| for this band
     * @param coins           reward, strictly positive — tier 0 is already the no-reward case
     */
    public record Tier(BigDecimal maxErrorSeconds, int coins) {

        public Tier {
            if (maxErrorSeconds == null || maxErrorSeconds.signum() <= 0) {
                throw new IllegalStateException(
                        "app.minigame.timer-stop.tiers[].max-error-seconds 는 0보다 커야 합니다: " + maxErrorSeconds);
            }
            // 오름차순 검사만으로는 -5, -1 같은 표가 통과한다. tier 0 이 이미 무보상이라
            // 보상 구간에 0·음수를 허용할 이유가 없다.
            if (coins <= 0) {
                throw new IllegalStateException(
                        "app.minigame.timer-stop.tiers[].coins 는 1 이상이어야 합니다: " + coins);
            }
        }
    }
}
