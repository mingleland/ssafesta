package com.example.ssafesta.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * The one place that decides whether a stored URL is acceptable (spec 004 §D09, 009 FR-004,
 * 016 FR-002).
 *
 * <p>Extracted from {@code BoothHomepageService} when spec 009 arrived with <b>five</b> URL fields
 * of its own. Six copies of the same six-step check is how one of them eventually loses a step —
 * and the step most likely to be lost is the ordering below, which is invisible until someone reads
 * the rejection message.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not know why a value is {@code null}. Presence — the difference between a missing key
 * and an explicit {@code null} — belongs to the caller, because only the caller knows whether a
 * missing key means "leave it alone" ({@code PATCH}) or "no value" ({@code POST}). Here
 * {@code null} is simply a valid value that needs no checking.
 */
public final class HttpUrlValidator {

    /** The V1 column width shared by every URL column in the schema. */
    public static final int MAX_LENGTH = 2048;

    private HttpUrlValidator() {
    }

    /**
     * Rejects on the first violated rule, with <b>that rule's own sentence</b> — never a generic
     * "invalid" (T-24: failures are not quietly flattened into one another).
     *
     * <p>Two orderings are load-bearing:
     *
     * <ul>
     *   <li><b>{@code null} first.</b> It is a value, not an error: a field nobody filled in, or one
     *       the owner just cleared. Returning it untouched is what lets the caller keep presence
     *       semantics to itself.
     *   <li><b>Scheme before host.</b> {@code javascript:alert(1)} and {@code data:text/html,…} have
     *       no host, so checking the host first ends the story with "malformed address" and the
     *       actual reason — a forbidden scheme — never reaches the user. Both orders block the same
     *       inputs; only the explanation differs, and the explanation is the point.
     * </ul>
     *
     * @param value       the raw value, or {@code null}
     * @param jsonField   the request field name, for {@link ApiErrorDetail#field}
     * @param displayName what to call it in the sentence ("홈페이지", "영상", "배포"…)
     * @return the value <b>byte for byte</b> — no trim, no lower-casing, no trailing-slash tidying.
     *         A validator that normalises breaks the round-trip guarantee callers assert on
     * @throws ApiException {@code VALIDATION_FAILED} with one field-level detail
     */
    public static String validate(String value, String jsonField, String displayName) {
        if (value == null) {
            return null; // 미등록이거나 해제 — 판정은 호출자 몫이다
        }
        if (value.isBlank()) {
            // Not silently treated as "clear it": a client that meant to clear sends an explicit
            // null, and one that sent "" almost certainly has a bug worth surfacing.
            throw reject(jsonField, displayName + " 주소를 입력해 주세요.");
        }
        if (value.length() > MAX_LENGTH) {
            throw reject(jsonField, displayName + " 주소가 너무 깁니다. (최대 " + MAX_LENGTH + "자)");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw reject(jsonField, displayName + " 주소 형식이 올바르지 않습니다.");
        }
        if (!uri.isAbsolute()) { // URI.isAbsolute() 가 곧 scheme != null 이다
            throw reject(jsonField, displayName + " 주소 형식이 올바르지 않습니다.");
        }
        if (!isAllowedScheme(uri.getScheme())) {
            throw reject(jsonField, displayName + " 주소는 http 또는 https로 시작해야 합니다.");
        }
        if (uri.getHost() == null) {
            throw reject(jsonField, displayName + " 주소 형식이 올바르지 않습니다.");
        }
        if (!isUsablePort(uri.getPort())) {
            throw reject(jsonField, displayName + " 주소의 포트 번호가 올바르지 않습니다. (1~65535)");
        }
        return value;
    }

    /**
     * {@code java.net.URI} does not bound the port — RFC 3986 defines it as {@code *DIGIT}, so
     * {@code https://example.com:99999} parses cleanly with a host and a port of 99999.
     *
     * <p>That address can never be connected to. Letting it through would put a dead link in front
     * of a visitor and quietly break SC-002, while the owner sees a saved value and no reason to
     * doubt it. Port {@code 0} is refused for the same reason: it means "any free port" to a
     * listener and nothing at all to a client.
     *
     * <p>Malformed ports never reach here — {@code :abc}, {@code :-1} and anything overflowing an
     * {@code int} make the authority registry-based, so {@code getHost()} returns {@code null} and
     * the previous rule already refused them as malformed. This rule exists for the narrow case the
     * parser accepts: digits that fit an {@code int} but not a port.
     *
     * @param port {@code -1} when the URL carries no port, which is the normal case
     */
    private static boolean isUsablePort(int port) {
        return port == -1 || (port >= 1 && port <= 65535);
    }

    /**
     * {@code http} is allowed, and that is deliberate.
     *
     * <p>These are destinations the user is sent to, not assets embedded in our page. An
     * {@code http} asset dies silently as mixed content; an {@code http} destination opens fine in
     * a new tab. Narrowing to https here would reject working links to prove a point the browser
     * already makes — and 009 SC-002 asks for the opposite (every registered link reachable).
     */
    private static boolean isAllowedScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** One field broke its constraint, so the rule stays {@code FIELD_INVALID} (#58 §3). */
    private static ApiException reject(String jsonField, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field(jsonField, message)), null);
    }
}
