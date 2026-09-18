package com.example.ssafesta.project;

import java.util.Optional;

/**
 * 우리가 발급한 로고 URL 의 유일한 해석기 (GitLab #241).
 *
 * <p>{@code thumbnailUrl} 에는 두 종류가 들어올 수 있다 — 사용자가 직접 넣은 외부 http(s) URL(기존
 * spec 009 C-03)과 이 서버가 발급한 로고 경로다. "그 URL 이 우리 로고인가, 어느 로고인가" 를 묻는
 * 곳이 네 군데(저장 시 검증 · 참조 끊김 감지 · 정리 배치 · 조회 권한)라서, 각자
 * {@code contains("...")} 를 쓰면 네 개가 조용히 갈린다. 판정은 여기 하나다.
 *
 * <p><b>절대 URL 이 아니라 경로다.</b> 절대 URL 로 만들려면 "이 배포의 공개 API origin" 이라는 새
 * 설정이 필요하고, 그 값이 환경마다 틀리면 DB 에 남은 URL 이 다른 환경에서 깨진다. 같은 오리진에서
 * 서비스되므로 {@code <img src="/api/v1/...">} 가 그대로 뜬다.
 */
public final class ManagedProjectLogoUrl {

    private static final String PREFIX = "/api/v1/booths/";
    private static final String MIDDLE = "/project-logos/";
    private static final String SUFFIX = "/content";

    private ManagedProjectLogoUrl() {
    }

    /** 이 로고를 가리키는 값. {@code thumbnailUrl} 에 그대로 들어간다. */
    public static String of(Long boothId, String logoId) {
        return PREFIX + boothId + MIDDLE + logoId + SUFFIX;
    }

    /** 우리가 발급한 로고 경로인가 — 형식만 본다. 그 로고가 실제로 있는지는 묻지 않는다. */
    public static boolean isManaged(String value) {
        return logoIdOf(value).isPresent();
    }

    /**
     * 그 값이 가리키는 {@code logoId}.
     *
     * <p>{@code booths/{id}/project-logos/{logoId}/content} 모양만 통과한다 — 뒤에 무엇이 붙은
     * 문자열(예: 질의 문자열이 붙은 URL)은 우리 값이 아니다. 우리는 이 형식만 발급한다.
     */
    public static Optional<String> logoIdOf(String value) {
        if (value == null || !value.startsWith(PREFIX) || !value.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        int middle = value.indexOf(MIDDLE);
        if (middle < 0) {
            return Optional.empty();
        }
        String booth = value.substring(PREFIX.length(), middle);
        String logoId = value.substring(middle + MIDDLE.length(), value.length() - SUFFIX.length());
        if (booth.isEmpty() || !booth.chars().allMatch(Character::isDigit)
                || logoId.isEmpty() || logoId.contains("/")) {
            return Optional.empty();
        }
        return Optional.of(logoId);
    }
}
