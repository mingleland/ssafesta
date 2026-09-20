package com.example.ssafesta.user;

import com.example.ssafesta.common.ProfanityFilter;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 닉네임 검사.
 *
 * <p>금칙어 목록과 정규화는 {@link ProfanityFilter} 로 옮겼다 — 월드 채팅이 같은 목록을 쓴다
 * (S15P21A604-792). 목록을 둘로 두면 갈리고, 어느 쪽이 정본인지 알 수 없게 된다. 여기 남은 것은
 * <b>닉네임에만 해당하는 것</b>뿐이다: 길이, 운영자 사칭 예약어, 연락처·URL 유도.
 */
@Component
public class NicknamePolicy {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 30;
    private static final List<String> RESERVED_TERMS = List.of(
            "관리자", "운영자", "운영진", "어드민", "관리팀", "운영팀", "개발자", "개발팀", "공식계정",
            "admin", "administrator", "manager", "moderator", "mod", "staff", "operator", "official", "system", "root", "superuser",
            "support", "developer", "devteam", "management", "ssafyadmin", "ssafystaff", "ssafestaadmin", "ssafestastaff");
    private static final Pattern CONTACT_OR_URL = Pattern.compile(
            "(01[016789]\\d{7,8}|https?|www|tme|openkakao|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})");
    private static final Pattern PHONE_NUMBER = Pattern.compile("01[016789]\\d{7,8}");

    public void validate(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.codePointCount(0, nickname.length()) < MIN_LENGTH
                || nickname.codePointCount(0, nickname.length()) > MAX_LENGTH) {
            throw new InvalidNicknameException();
        }
        String normalized = ProfanityFilter.normalize(nickname);
        String digitsOnly = nickname.replaceAll("\\D", "");
        if (normalized.isBlank() || PHONE_NUMBER.matcher(digitsOnly).matches() || CONTACT_OR_URL.matcher(normalized).find()
                || ProfanityFilter.contains(nickname)
                || RESERVED_TERMS.stream().anyMatch(normalized::contains)
                || isReservedBrandOnly(normalized)) {
            throw new InvalidNicknameException();
        }
    }

    private boolean isReservedBrandOnly(String normalized) {
        return normalized.equals("ssafy") || normalized.equals("ssafesta") || normalized.equals("싸피");
    }
}
