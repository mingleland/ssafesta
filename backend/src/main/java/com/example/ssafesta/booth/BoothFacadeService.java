package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booth's exterior presentation (spec 005 FR-018).
 *
 * <p>Four fixed fields, not a layout. spec 006 renders the outside from a shared building prefab
 * and only varies name, logo, colour and sign — free placement out there would mean every slot in
 * the world could look like anything, which is a different feature with different limits.
 */
@Service
public class BoothFacadeService {

    private static final Set<String> THEME_CODES = Set.of("DEFAULT", "SSAFY_BLUE", "WARM", "MONO");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int MAX_SIGN_TEXT = 60;
    private static final int MAX_URL = 2048;

    private final BoothEditorGuard editorGuard;
    private final BoothLeaseRepository leases;

    public BoothFacadeService(BoothEditorGuard editorGuard, BoothLeaseRepository leases) {
        this.editorGuard = editorGuard;
        this.leases = leases;
    }

    @Transactional
    public FacadeView update(Long boothId, Long userId, FacadeCommand command) {
        Booth booth = editorGuard.requireEditor(boothId, userId);

        // An expired booth shows no facade at all (spec 004 만료 계약), so editing one would be
        // changing something nobody can see — and the same predicate decides both.
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));

        String themeCode = command.themeCode() == null ? "DEFAULT" : command.themeCode();
        if (!THEME_CODES.contains(themeCode)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 테마입니다: " + themeCode);
        }
        String primaryColor = paletteColor(command.primaryColor());
        if (command.signText() != null && command.signText().length() > MAX_SIGN_TEXT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "간판 문구는 " + MAX_SIGN_TEXT + "자까지입니다.");
        }
        validateLogoUrl(command.logoUrl());

        booth.changeFacade(themeCode, primaryColor, command.signText(), command.logoUrl());
        return FacadeView.of(booth);
    }

    /**
     * Format, then spelling, then membership — the twelve-colour palette (contracts/layout-api.md §6).
     *
     * <p>The order is what keeps the message useful. {@code "파랑"} is not a colour at all and is
     * told so; {@code "#123456"} is a perfectly well-formed colour that simply is not on the
     * palette, and gets a different sentence. Checking membership first would answer both with the
     * palette message and the owner would never learn which mistake they made.
     *
     * <p>Only values arriving <i>now</i> are checked. Colours stored before the whitelist existed
     * keep rendering and are validated on their next save — the contract chose that over a forced
     * migration.
     */
    private String paletteColor(String primaryColor) {
        if (primaryColor == null) {
            return null;
        }
        if (!HEX_COLOR.matcher(primaryColor).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "대표색은 #RRGGBB 형식이어야 합니다.");
        }
        String normalized = FacadePalette.normalize(primaryColor);
        if (!FacadePalette.contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "팔레트에 없는 색입니다.");
        }
        return normalized;
    }

    /**
     * Only {@code https}.
     *
     * <p>The world is served over TLS, so an {@code http} logo would be blocked as mixed content and
     * the booth would simply show nothing — a failure the owner could not diagnose from the outside.
     */
    private void validateLogoUrl(String logoUrl) {
        if (logoUrl == null) {
            return;
        }
        if (logoUrl.length() > MAX_URL) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "로고 URL이 너무 깁니다.");
        }
        if (!logoUrl.startsWith("https://")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "로고 URL은 https로 시작해야 합니다.");
        }
    }

    public record FacadeCommand(String themeCode, String primaryColor, String signText, String logoUrl) { }

    public record FacadeView(String themeCode, String primaryColor, String signText, String logoUrl) {

        public static FacadeView of(Booth booth) {
            return new FacadeView(booth.getFacadeThemeCode(), booth.getFacadePrimaryColor(),
                    booth.getFacadeSignText(), booth.getFacadeLogoUrl());
        }
    }
}
