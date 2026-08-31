package com.example.ssafesta.internal.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service tokens for calls that come <b>into</b> Spring from FastAPI (GitLab #102, 2026-08-25).
 *
 * <p>Two tokens exist, one per direction, and they are not interchangeable: the AI→Spring one lives
 * in the same process as the Worker that parses user-uploaded PDFs, so the wider exposure must not
 * reach the narrower side. Only the receiving half is here — the sending token that Spring attaches
 * to FastAPI calls belongs to S15P21A604-175.
 *
 * <p>A comma-separated list, not two variables: the sender uses the first value and the receiver
 * accepts any of them, so "which one is current" is expressed by the value itself and rotation
 * ({@code [old] → [old,new] → [new,old] → [new]}) needs no separate promotion step.
 *
 * @param aiToSpringTokens the raw comma-separated value, <b>not</b> a {@code List<String>} — see
 *                         {@link #aiToSpringTokenList()}
 */
@ConfigurationProperties("app.internal")
public record InternalTokenProperties(String aiToSpringTokens) {

    private static final int MAX_TOKENS = 2;

    public InternalTokenProperties {
        List<String> parsed = split(aiToSpringTokens);
        // Blank first: an empty element is the failure the split limit below exists to expose.
        if (parsed.isEmpty() || parsed.stream().anyMatch(String::isEmpty)) {
            throw new IllegalStateException(
                    "app.internal.ai-to-spring-tokens 에 빈 항목이 있습니다. 콤마 구분 1~2개여야 합니다.");
        }
        // Surrounding whitespace is refused rather than trimmed. Silently normalising a secret makes
        // the configured value differ from the compared value, and that gap shows up only as a
        // runtime 401 — a boot failure says it at deploy time instead. The AI side's _parse_csv
        // strips and drops blanks, so on every value Spring accepts that stripping is a no-op and
        // both sides are guaranteed to hold the same list.
        if (parsed.stream().anyMatch(token -> !token.equals(token.strip()))) {
            throw new IllegalStateException(
                    "app.internal.ai-to-spring-tokens 의 항목에 앞뒤 공백이 있습니다. 공백 없이 설정해 주세요.");
        }
        if (Set.copyOf(parsed).size() != parsed.size()) {
            throw new IllegalStateException(
                    "app.internal.ai-to-spring-tokens 에 같은 값이 두 번 있습니다. 회전은 서로 다른 두 값입니다.");
        }
        if (parsed.size() > MAX_TOKENS) {
            throw new IllegalStateException("app.internal.ai-to-spring-tokens 는 최대 " + MAX_TOKENS
                    + "개입니다 (현재 " + parsed.size() + "개). 회전에 필요한 것은 옛 값 하나뿐입니다.");
        }
    }

    /** The accepted tokens, in configured order. The first is the one senders are expected to use. */
    public List<String> aiToSpringTokenList() {
        return split(aiToSpringTokens);
    }

    /**
     * Splits with a negative limit on purpose.
     *
     * <p>{@code "a,".split(",")} drops the trailing empty element and yields one valid-looking
     * token, so the plain form would hide the exact typo the validation above is written to catch.
     */
    private static List<String> split(String raw) {
        return raw == null || raw.isEmpty() ? List.of() : Arrays.asList(raw.split(",", -1));
    }
}
