package com.example.ssafesta.eventshop;

import com.example.ssafesta.common.ApiException;
import java.util.Set;

/**
 * Who a bought prize is handed to (GitLab #239, S15P21A604-885).
 *
 * <p>Prizes are gift certificates, so a purchase that does not say who receives it cannot be
 * fulfilled. The three values mirror the FE contract
 * ({@code shared/contracts/purchaseRecipient.ts}) field for field, and arrive flat in the request
 * body — this record is where they become one thing the rest of the code can pass around.
 *
 * <p>The campus vocabulary lives here rather than in the controller because the validation has to
 * be reachable from the service: {@link EventShopService#purchase} is the last gate every purchase
 * path crosses, and a check that only the controller performs is a check the next caller skips.
 */
public record PurchaseRecipient(String campus, String teamName, String recipientName) {

    /** The five SSAFY campuses, same list and spelling as {@code CAMPUS_OPTIONS} on the FE. */
    public static final Set<String> CAMPUSES = Set.of("서울", "대전", "광주", "구미", "부울경");

    private static final int MAX_TEAM_NAME = 50;
    private static final int MAX_RECIPIENT_NAME = 50;

    /**
     * @return the same recipient with surrounding whitespace removed
     * @throws ApiException {@code VALIDATION_FAILED} naming the offending field — a missing or blank
     *                      value, a name longer than the column, or a campus outside {@link #CAMPUSES}
     */
    public PurchaseRecipient validated() {
        String campus = trimmed(this.campus, "campus");
        if (!CAMPUSES.contains(campus)) {
            throw ApiException.fieldInvalid("campus", "서울·대전·광주·구미·부울경 중 하나여야 합니다.");
        }
        String teamName = trimmed(this.teamName, "teamName");
        requireLength(teamName, "teamName", MAX_TEAM_NAME);
        String recipientName = trimmed(this.recipientName, "recipientName");
        requireLength(recipientName, "recipientName", MAX_RECIPIENT_NAME);
        return new PurchaseRecipient(campus, teamName, recipientName);
    }

    /** Whether this row predates the field — all three absent, not one of them blank. */
    boolean isAbsent() {
        return campus == null && teamName == null && recipientName == null;
    }

    private static String trimmed(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.fieldInvalid(field, "필수입니다.");
        }
        return value.trim();
    }

    private static void requireLength(String value, String field, int max) {
        // Code points, not String.length(): the column is sized in characters and a name of
        // emoji-length would be cut by the database instead of refused here.
        if (value.codePointCount(0, value.length()) > max) {
            throw ApiException.fieldInvalid(field, max + "자 이내여야 합니다.");
        }
    }
}
