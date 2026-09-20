package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.HttpUrlValidator;
import com.example.ssafesta.common.PresenceField;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    private final BoothAccessGuard accessGuard;

    public BoothHomepageService(BoothAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    @Transactional
    public HomepageView update(Long boothId, Long userId, HomepageCommand command) {
        // Same reasoning as the facade: an expired booth shows nothing to anyone, so editing it
        // would be changing something invisible (spec 004 만료 계약).
        Booth booth = accessGuard.requireActiveEditor(boothId, userId);

        booth.changeHomepageUrl(validated(command));
        return new HomepageView(booth.getHomepageUrl());
    }

    /**
     * <b>Presence before value.</b> A missing key and an explicit {@code null} are not the same
     * request. Only the explicit one clears the URL; {@code {}} is refused. Treating them alike
     * would let one serialisation slip on the client silently delete a registered page — which is
     * why the command is not a {@code record} (data-model §3 #0, §5).
     *
     * <p>That distinction is the only thing left here. Everything about the <i>value</i> — length,
     * parse, scheme-before-host, byte-for-byte round trip — moved to
     * {@link HttpUrlValidator#validate}, which spec 009 shares across five more URL fields.
     */
    private String validated(HomepageCommand command) {
        if (command == null || !command.homepageUrl.isPresent()) {
            throw reject("homepageUrl 필드가 필요합니다.");
        }
        // 값의 형식 판정은 HttpUrlValidator 가 소유한다 (spec 009 가 URL 필드 5 개를 들고 오면서
        // 같은 6 단계가 6 벌이 될 상황이었다). 여기 남은 것은 presence — {} 와 {"homepageUrl":null}
        // 의 구분이고, 그것만은 endpoint 마다 뜻이 달라 옮길 수 없다.
        //
        // 문구는 바이트 단위로 이전과 같다. displayName "홈페이지" 가 기존 네 문장을 그대로 만든다.
        return HttpUrlValidator.validate(command.homepageUrl.value(), "homepageUrl", "홈페이지");
    }

    /** One field broke its constraint, so the rule stays {@code FIELD_INVALID} (#58 §3). */
    private ApiException reject(String message) {
        return ApiException.fieldInvalid("homepageUrl", message);
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

        private final PresenceField<String> homepageUrl = new PresenceField<>();

        @JsonProperty("homepageUrl")
        void setHomepageUrl(String value) {
            homepageUrl.set(value);
        }
    }

    public record HomepageView(String homepageUrl) { }
}
