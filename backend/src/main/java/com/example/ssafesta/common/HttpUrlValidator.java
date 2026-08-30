package com.example.ssafesta.common;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Whether a URL a user typed is acceptable as a <b>destination</b> (spec 004 §D09, 009 FR-004,
 * 016 FR-002).
 *
 * <p>Extracted from {@code BoothHomepageService} when spec 009 arrived with <b>five</b> URL fields
 * of its own. Six copies of the same check is how one of them eventually loses a step — and the
 * step most likely to be lost is the ordering below, which is invisible until someone reads the
 * rejection message.
 *
 * <p><b>Not every URL in this codebase comes here.</b> {@code BoothFacadeService} keeps its own
 * rule for the logo, and deliberately: a logo is embedded in our page so it is https-only, while
 * these fields are places the user is sent to and {@code http} works for that. Folding the two
 * together would mean one of them silently loosening. Moving the facade here is a separate change
 * that first has to decide which rule wins.
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
        // userinfo 는 어느 경로에서도 거부한다 — ASCII 든 IDN 이든 규칙은 하나여야 한다.
        // 초판은 IDN 경로에만 걸어 두어 https://한글도메인.com@evil.example.com 이 통과했다.
        if (uri.getUserInfo() != null || uri.getRawAuthority() != null
                && uri.getRawAuthority().indexOf('@') >= 0) {
            throw reject(jsonField, displayName + " 주소에 사용자 정보(@)를 넣을 수 없습니다.");
        }
        URI resolved = serverParsed(uri);
        if (resolved == null) {
            throw reject(jsonField, displayName + " 주소 형식이 올바르지 않습니다.");
        }
        // 해석된 URI 에서 한 번 더 본다. 위 raw 검사는 ASCII '@' 만 잡는데, 전각 '＠'(U+FF20)은
        // IDN 매핑을 거치며 '@' 가 되어 그때서야 userinfo 가 생긴다 — https://한글.com＠evil.com 이
        // evil.com 으로 해석되는 자리다.
        if (resolved.getUserInfo() != null) {
            throw reject(jsonField, displayName + " 주소에 사용자 정보(@)를 넣을 수 없습니다.");
        }
        if (!isUsablePort(resolved.getPort())) {
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

    /**
     * {@code java.net.URI} parses the authority by RFC 2396, which predates internationalised
     * domains — {@code https://한글도메인.com} comes back with a {@code null} host and would be
     * refused as malformed. This is a Korean product; a Korean domain is not a malformed one.
     *
     * <p>The ASCII form is used only to <b>ask the question</b>. The stored value stays exactly what
     * the user sent (invariant I-3), so the browser still receives the Unicode form it resolves.
     *
     * <p>An underscore host ({@code https://my_host.example.com}) stays refused. It is invalid under
     * RFC 1123 and appears only on internal names, which are not destinations a visitor can reach —
     * the case this validator exists for.
     *
     * <p><b>Only the host is converted, and the result must still be a bare authority.</b> Handing
     * {@code IDN.toASCII} the whole authority looked simpler and was wrong: it treats its argument
     * as a domain name, so {@code user@한글.com} came back as {@code xn--user@-lt1tk15s.com} — a
     * different name entirely. Anything that re-parses into a path, query or fragment is refused
     * rather than validated, because the URI we checked would no longer be the string we store
     * (invariant I-3 cuts both ways).
     *
     * <p>Userinfo is checked <b>twice</b>, and both are needed. {@link #validate} looks at the raw
     * authority before this method runs, which catches an ASCII {@code @} that the registry-based
     * parse hides. It then looks at the resolved URI afterwards, because a full-width {@code ＠}
     * (U+FF20) is not an {@code @} until IDN mapping makes it one — {@code https://한글.com＠evil.com}
     * resolves to {@code evil.com} and would otherwise pass as a plain host.
     *
     * <p>Separators that map to a real structure ({@code ／} → {@code /}, {@code ？} → {@code ?})
     * are caught by {@link #isPlainAuthority}. An ideographic full stop ({@code 。} → {@code .}) is
     * <b>not</b> caught, and should not be: browsers apply the same mapping, so it is a genuine
     * label separator rather than a smuggled one.
     */
    private static URI serverParsed(URI uri) {
        if (uri.getHost() != null) {
            return uri;
        }
        String authority = uri.getAuthority();
        if (authority == null) {
            return null;
        }
        // '@' 는 validate 가 이미 걸렀다 — 여기 오는 authority 에는 userinfo 가 없다.
        String host = authority;
        String port = "";
        int lastColon = authority.lastIndexOf(':');
        if (lastColon >= 0) {
            host = authority.substring(0, lastColon);
            port = authority.substring(lastColon); // ":8080" — 숫자 판정은 아래 재파싱이 한다
        }
        try {
            // host 만 변환한다. authority 를 통째로 넘기면 변환 결과가 다른 구조로 재파싱될 수
            // 있고, 그러면 검증한 URI 와 저장하는 문자열이 다른 곳을 가리킨다.
            //
            // 포트 판정도 이 결과로 해야 한다 — 원본은 authority 가 registry-based 라
            // getPort() 가 항상 -1 이고, 그러면 한글 도메인만 포트 규칙을 빠져나간다.
            String asciiHost = IDN.toASCII(host);
            if (!isHostLabels(asciiHost)) {
                return null;
            }
            URI ascii = new URI(uri.getScheme() + "://" + asciiHost + port);
            return isPlainAuthority(ascii) ? ascii : null;
        } catch (IllegalArgumentException | URISyntaxException retryFailed) {
            return null;
        }
    }

    /**
     * IDN 이 내놓은 host 가 <b>이름표들</b>로만 이뤄졌는지 — 구조 문자가 섞이지 않았는지 본다.
     *
     * <p>이 한 줄이 전각 구분자 전부를 한꺼번에 막는다. 그 전에는 문자를 하나씩 쫓아다녔고
     * 그때마다 새로운 것이 나왔다 — {@code ＠}(U+FF20) 다음이 {@code ：}(U+FF1A)였다.
     * {@code IDN.toASCII} 는 이름표를 정규화하는 함수이므로 <b>구조 문자를 만들어 내면 그것은
     * 이미 이름이 아니다.</b> 어떤 코드포인트가 무엇으로 매핑되는지 열거하는 대신 결과가
     * 이름의 모양인지 묻는다.
     *
     * <ul>
     *   <li>{@code 。}(U+3002) → {@code .} 는 통과한다. 점은 이름표 구분자이고 브라우저도 같게 읽는다
     *   <li>{@code ：} → {@code :} 는 거부한다. 원본에는 포트가 없는데 재조립하면 포트가 생겨,
     *       검증한 구조와 저장되는 문자열이 갈린다 ({@code https://한글.com：8080/x})
     *   <li>밑줄({@code _})도 여기서 걸린다. RFC 1123 위반이라 그대로 거부를 유지한다
     * </ul>
     *
     * <p>IPv6 는 대괄호를 쓰지만 이 경로에 오지 않는다 — {@code getHost()} 가 이미 값을 준다.
     */
    private static boolean isHostLabels(String asciiHost) {
        if (asciiHost.isEmpty()) {
            return false;
        }
        for (int i = 0; i < asciiHost.length(); i++) {
            char c = asciiHost.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 재조립한 URI 가 <b>host(+port) 하나로만</b> 이뤄졌는지 확인한다.
     *
     * <p>변환 결과에 {@code /}·{@code ?}·{@code #} 가 섞여 들어오면 {@code new URI} 가 그것을
     * 경로·질의·조각으로 갈라 읽는다. 그러면 우리가 host 라고 판정한 것이 원본 authority 의
     * 일부에 지나지 않게 되고, 검증을 통과한 주소와 저장되는 주소가 갈린다.
     */
    private static boolean isPlainAuthority(URI ascii) {
        return ascii.getHost() != null
                && (ascii.getPath() == null || ascii.getPath().isEmpty())
                && ascii.getQuery() == null
                && ascii.getFragment() == null;
    }

    /** One field broke its constraint, so the rule stays {@code FIELD_INVALID} (#58 §3). */
    private static ApiException reject(String jsonField, String message) {
        return ApiException.fieldInvalid(jsonField, message);
    }
}
