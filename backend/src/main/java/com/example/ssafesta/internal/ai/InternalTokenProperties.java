package com.example.ssafesta.internal.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service tokens for the two internal directions between Spring and FastAPI (GitLab #102,
 * 2026-08-25).
 *
 * <p>Two tokens exist, one per direction, and they are not interchangeable: the AI→Spring one lives
 * in the same process as the Worker that parses user-uploaded PDFs, so the wider exposure must not
 * reach the narrower side. The AI side refuses to boot when the two sets overlap
 * ({@code festa-ai/app/core/config.py}), so the same value must never appear in both.
 *
 * <p>A comma-separated list, not two variables: the sender uses the first value and the receiver
 * accepts any of them, so "which one is current" is expressed by the value itself and rotation
 * ({@code [old] → [old,new] → [new,old] → [new]}) needs no separate promotion step.
 *
 * @param aiToSpringTokens the tokens Spring <b>accepts</b> on {@code /internal/ai/**}. Raw
 *                         comma-separated value, <b>not</b> a {@code List<String>} — see
 *                         {@link #aiToSpringTokenList()}
 * @param springToAiTokens the tokens Spring <b>sends</b> when it calls FastAPI. Only the first is
 *                         ever sent; the rest exist so a rotation can be configured on both sides
 *                         before the sender moves (S15P21A604-175)
 */
@ConfigurationProperties("app.internal")
public record InternalTokenProperties(String aiToSpringTokens, String springToAiTokens) {

    private static final int MAX_TOKENS = 2;

    public InternalTokenProperties {
        validate(aiToSpringTokens, "app.internal.ai-to-spring-tokens");
        validate(springToAiTokens, "app.internal.spring-to-ai-tokens");
    }

    /** The accepted tokens, in configured order. The first is the one senders are expected to use. */
    public List<String> aiToSpringTokenList() {
        return split(aiToSpringTokens);
    }

    /**
     * The tokens Spring may present to FastAPI, in configured order.
     *
     * <p>Callers send {@link #springToAiToken()}; this exists so a rotation is visible in
     * configuration rather than requiring a redeploy to switch.
     */
    public List<String> springToAiTokenList() {
        return split(springToAiTokens);
    }

    /** The token Spring actually sends. The first value, by the rotation convention above. */
    public String springToAiToken() {
        return springToAiTokenList().get(0);
    }

    /**
     * The same four refusals for either direction, with the property path in the message.
     *
     * <p>Whitespace is refused rather than trimmed. Silently normalising a secret makes the
     * configured value differ from the compared value, and that gap shows up only as a runtime 401 —
     * a boot failure says it at deploy time instead. The AI side's {@code _parse_csv} strips and
     * drops blanks, so on every value Spring accepts that stripping is a no-op and both sides are
     * guaranteed to hold the same list.
     */
    private static void validate(String raw, String path) {
        List<String> parsed = split(raw);
        // Blank first: an empty element is the failure the split limit below exists to expose.
        if (parsed.isEmpty() || parsed.stream().anyMatch(String::isEmpty)) {
            throw new IllegalStateException(path + " 에 빈 항목이 있습니다. 콤마 구분 1~2개여야 합니다.");
        }
        // An unresolved placeholder passes every check below it — "${INTERNAL_SPRING_TO_AI_TOKENS}"
        // is one non-empty element with no surrounding space and no duplicate. Left alone it
        // becomes the token itself, and every call in that direction answers 401 forever: a
        // deployment that forgot one variable looks exactly like the other side being broken. Same
        // hole ObjectStorageProperties closes for the same reason (T-101).
        if (parsed.stream().anyMatch(token -> token.startsWith("${") && token.endsWith("}"))) {
            throw new IllegalStateException(
                    path + " 이 해석되지 않았습니다 — 배포에서 " + raw + " 를 주입해야 합니다.");
        }
        if (parsed.stream().anyMatch(token -> !token.equals(token.strip()))) {
            throw new IllegalStateException(path + " 의 항목에 앞뒤 공백이 있습니다. 공백 없이 설정해 주세요.");
        }
        if (Set.copyOf(parsed).size() != parsed.size()) {
            throw new IllegalStateException(path + " 에 같은 값이 두 번 있습니다. 회전은 서로 다른 두 값입니다.");
        }
        if (parsed.size() > MAX_TOKENS) {
            throw new IllegalStateException(path + " 는 최대 " + MAX_TOKENS + "개입니다 (현재 "
                    + parsed.size() + "개). 회전에 필요한 것은 옛 값 하나뿐입니다.");
        }
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
