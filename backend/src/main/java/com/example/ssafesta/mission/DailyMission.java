package com.example.ssafesta.mission;

import com.example.ssafesta.common.ApiException;

/**
 * The fixed daily-mission catalogue whose copy belongs to clients (GitLab #233).
 *
 * <p>The API sends only this stable identifier, reward and progress. Titles and descriptions stay
 * in FE/Unity so a copy change cannot require a backend release.
 */
public enum DailyMission {

    AI_CONSULT(1),
    SURVEY_ANSWER(1),
    STRIKER_PLAY_3(3),
    STRIKER_SCORE(1),
    SLOT_PLAY_3(3),
    SLOT_WIN(1),
    BOOTH_VISIT_3(3),
    BOOTH_VISIT_6(6),
    WORLD_ENTER(1);

    public static final int REWARD_COIN = 15;
    public static final int DAILY_CAP_COIN = 135;

    private final int goal;

    DailyMission(int goal) {
        this.goal = goal;
    }

    public int goal() {
        return goal;
    }

    public static DailyMission require(String raw) {
        try {
            return DailyMission.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApiException.fieldInvalid("missionId", "알 수 없는 일일 미션입니다: " + raw);
        }
    }
}
