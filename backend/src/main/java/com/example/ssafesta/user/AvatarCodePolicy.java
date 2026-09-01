package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The only two things the server is allowed to say about an avatar encoding (spec 013a contract
 * §서버 검증): it is short enough, and it is made of characters the encoder can produce.
 *
 * <p><b>This policy never parses the string.</b> Its structural validation remains length and
 * charset only, so Unity can evolve colour and extension segments without a server release.
 * Ownership is a separate concern: {@link AvatarWornItems} tolerantly reads only the modular
 * encoding's {@code i=} segment, and treats an unknown shape as carrying no shop-item claim.
 *
 * <p>Nothing is repaired, either. No trim, no case fold, no default substitution — a rejected value
 * comes back as a refusal the user can read (FR-012), never as a different value they did not
 * choose. T-24 was that failure exactly: a length overrun swallowed on the way in, leaving a button
 * that appeared to do nothing.
 *
 * @see NicknamePolicy the same shape for the other user-supplied string on this account
 */
@Component
public class AvatarCodePolicy {

    /**
     * Unity's {@code AvatarAppearance.MaxEncodedLength}.
     *
     * <p>Kept identical on purpose: the client already refuses to emit anything longer, so this
     * bound rejects only forged payloads and never a legitimate encoding. <b>Do not lower it.</b>
     * The modular form (<code>fa|3=SK_Hair_Long_01|…</code>) carries asset part names verbatim and
     * grows as parts are added — the preset form (<code>sk_01</code>) is short enough to make a
     * small limit look safe. 헌법 23조 voided the old "29~32자" figure for this reason.
     */
    static final int MAX_LENGTH = 3800;

    private static final char FIRST_PRINTABLE = 0x20;
    private static final char LAST_PRINTABLE = 0x7E;

    static final String FIELD = "avatarCode";

    public void validate(String avatarCode) {
        if (avatarCode == null || avatarCode.isBlank()) {
            throw rejected("아바타 코드를 입력해 주세요.");
        }
        if (avatarCode.length() > MAX_LENGTH) {
            throw rejected("아바타 코드가 너무 깁니다. (최대 " + MAX_LENGTH + "자, 현재 " + avatarCode.length() + "자)");
        }
        if (!isPrintableAscii(avatarCode)) {
            throw rejected("아바타 코드에 쓸 수 없는 문자가 있습니다.");
        }
    }

    /**
     * Printable ASCII only.
     *
     * <p>Wider than the encoder's current alphabet, and that is the point. Pinning this to
     * <code>[A-Za-z0-9_|=]</code> would match today's output and then reject the first asset whose
     * part name contains a hyphen or a space — a T-24 shaped failure rebuilt as a validation rule.
     * What this does exclude is control characters (which corrupt logs) and non-ASCII (which the
     * encoder cannot emit, so accepting it only widens what a forged request may store — 헌법 16조).
     */
    private static boolean isPrintableAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < FIRST_PRINTABLE || character > LAST_PRINTABLE) {
                return false;
            }
        }
        return true;
    }

    /**
     * One field, one rule name.
     *
     * <p>{@code rule} stays {@link ApiErrorDetail#FIELD_INVALID} and the field name goes in
     * {@code field} — #58 §3 closed the rule vocabulary so clients can branch on a whitelist, and a
     * bespoke {@code AVATAR_CODE_INVALID} would reopen it for no gain. The differing part is the
     * message, which is what the user actually reads.
     */
    private static ApiException rejected(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field(FIELD, message)), null);
    }
}
