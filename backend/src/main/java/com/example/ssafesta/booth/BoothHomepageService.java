package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The page a booth's laptop opens (spec 016 FR-001~FR-003, contracts/homepage-api.md §2).
 *
 * <p>Deliberately the same shape as {@link BoothFacadeService}: editor guard, valid lease, validate,
 * write straight to the {@code booths} row. Draft/Publish is not involved — what the gate in
 * {@link BoothQueryService} decides is whether visitors <i>see</i> the value, not whether it is
 * saved (research R-03).
 *
 * <p>The URL lives on the booth and never in the layout JSON (C-01, 확정 2026-08-26 #97), so
 * nothing here touches the spec 005 contract.
 */
@Service
public class BoothHomepageService {

    /** The V1 column width, and the same ceiling the facade logo uses. */
    private static final int MAX_URL = 2048;

    private final BoothEditorGuard editorGuard;
    private final BoothLeaseRepository leases;

    public BoothHomepageService(BoothEditorGuard editorGuard, BoothLeaseRepository leases) {
        this.editorGuard = editorGuard;
        this.leases = leases;
    }

    @Transactional
    public HomepageView update(Long boothId, Long userId, HomepageCommand command) {
        Booth booth = editorGuard.requireEditor(boothId, userId);

        // Same reasoning as the facade: an expired booth shows nothing to anyone, so editing it
        // would be changing something invisible (spec 004 만료 계약).
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));

        booth.changeHomepageUrl(validated(command));
        return new HomepageView(booth.getHomepageUrl());
    }

    /**
     * Rules in the order of data-model §3, rejecting on the first violation with <b>its own</b>
     * sentence — the owner has to be able to tell which rule they broke (T-24: failures are not
     * quietly flattened).
     *
     * <p>Two orderings here are load-bearing:
     *
     * <ul>
     *   <li><b>Presence before null.</b> A missing key and an explicit {@code null} are not the
     *       same request. Only the explicit one clears the URL; {@code {}} is refused. Treating them
     *       alike would let one serialisation slip on the client silently delete a registered page —
     *       which is why the command is not a {@code record} (data-model §3 #0, §5).
     *   <li><b>Scheme before host.</b> {@code javascript:alert(1)} and {@code data:text/html,…} have
     *       no host, so checking the host first ends the story with "malformed address" and the
     *       actual reason — a forbidden scheme — never reaches the user. Both orders block the same
     *       inputs; only the explanation differs.
     * </ul>
     */
    private String validated(HomepageCommand command) {
        if (command == null || !command.present) {
            throw reject("homepageUrl 필드가 필요합니다.");
        }
        String url = command.homepageUrl;
        if (url == null) {
            return null; // 등록 해제 (§5)
        }
        if (url.isBlank()) {
            throw reject("홈페이지 주소를 입력해 주세요.");
        }
        if (url.length() > MAX_URL) {
            throw reject("홈페이지 주소가 너무 깁니다. (최대 " + MAX_URL + "자)");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw reject("홈페이지 주소 형식이 올바르지 않습니다.");
        }
        if (!uri.isAbsolute()) { // URI.isAbsolute() 가 곧 scheme != null 이다
            throw reject("홈페이지 주소 형식이 올바르지 않습니다.");
        }
        if (!isAllowedScheme(uri.getScheme())) {
            throw reject("홈페이지 주소는 http 또는 https로 시작해야 합니다.");
        }
        if (uri.getHost() == null) {
            throw reject("홈페이지 주소 형식이 올바르지 않습니다.");
        }
        return url; // 원문 그대로 — trim·정규화 없음 (data-model §2 왕복 무손실)
    }

    /**
     * {@code http} is allowed on purpose, unlike the facade logo.
     *
     * <p>A logo is embedded in our own page, so an {@code http} one dies silently as mixed content.
     * A homepage is a destination: when the iframe is refused the overlay opens a new tab instead
     * (US3), and that works over {@code http}. FR-002 lists both schemes; the mixed-content warning
     * is the React layer's (research R-04).
     */
    private static boolean isAllowedScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** One field broke its constraint, so the rule stays {@code FIELD_INVALID} (#58 §3). */
    private ApiException reject(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field("homepageUrl", message)), null);
    }

    /**
     * Not a {@code record}, and that is the whole point.
     *
     * <p>A record cannot tell {@code {}} from {@code {"homepageUrl": null}} — both arrive as
     * {@code null}. Since an explicit {@code null} is how a booth clears its URL, that collapse
     * would turn a client that forgot the field into a client that deleted the page. Jackson calls
     * the setter only when the key is present, so {@code present} carries exactly the distinction
     * the contract needs.
     */
    public static final class HomepageCommand {

        private String homepageUrl;
        private boolean present;

        @JsonProperty("homepageUrl")
        void setHomepageUrl(String homepageUrl) {
            this.homepageUrl = homepageUrl;
            this.present = true;
        }
    }

    public record HomepageView(String homepageUrl) { }
}
