package com.example.ssafesta.project;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트 대표 이미지 업로드 (GitLab #241, spec 009 C-03 개정).
 *
 * <p>업로드는 <b>부스</b> 아래에 있다 — 프로젝트를 만들기 전에 로고를 올리는 흐름이 요구사항이라
 * {@code projectId} 를 기준으로 삼을 수 없다.
 */
@RestController
@RequestMapping("/api/v1/booths/{boothId}/project-logos")
@Tag(name = "Project Logo", description = "프로젝트 대표 이미지 업로드 (spec 009)")
public class ProjectLogoController {

    private final ProjectLogoService logos;

    public ProjectLogoController(ProjectLogoService logos) {
        this.logos = logos;
    }

    @Operation(summary = "업로드 시작 — presigned PUT 발급",
            description = """
                    파일을 올릴 자리를 하나 받는다. 순서는 **시작 → PUT → 완료** 세 단계다.

                    1. 이 endpoint 로 `contentType` 과 `byteSize` 를 선언하면 `uploadUrl` 이 나온다.
                    2. 그 URL 로 파일을 그대로 `PUT` 한다. **`Content-Type` 은 `requiredContentType` 과
                       같아야 한다** — 서명에 박혀 있어 다른 값이면 저장소가 403 으로 거절한다.
                    3. `POST .../{logoId}/complete` 를 부른다. 서버가 실제 바이트를 읽어 검증한다.

                    선언한 형식·크기는 **거절에만** 쓴다. 통과는 3단계가 실제 파일을 보고 정한다 —
                    위장한 MIME 은 여기서 통과해도 완료에서 `FAILED` 가 된다.

                    허용: PNG · JPEG · GIF · WebP, 5MB 이하, 한 변 4096px 이하 (애니메이션 WebP 거부).

                    `uploadUrl` 은 10분을 산다. 그 안에 `PUT` 과 완료를 마쳐야 하고, 지나면 새로 시작한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "업로드 자리 발급"),
            @ApiResponse(responseCode = "403",
                    description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(내 부스가 아니다)"),
            @ApiResponse(responseCode = "404", description = "`BOOTH_NOT_FOUND`"),
            @ApiResponse(responseCode = "409",
                    description = "`BOOTH_LEASE_EXPIRED`(임대 만료) 또는 `PROJECT_LOGO_QUOTA_EXCEEDED`"
                            + "(올렸지만 저장하지 않은 이미지가 너무 많다)"),
            @ApiResponse(responseCode = "413", description = "`PROJECT_LOGO_TOO_LARGE`"),
            @ApiResponse(responseCode = "415", description = "`PROJECT_LOGO_TYPE_UNSUPPORTED`")})
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ProjectLogoService.IssuedGrant start(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "내 부스 식별자", example = "7")
                                                @PathVariable Long boothId,
                                                @RequestBody StartRequest request) {
        Long userId = MemberPrincipal.requireMemberId(jwt);
        long byteSize = request == null || request.byteSize() == null ? 0L : request.byteSize();
        String contentType = request == null ? null : request.contentType();
        return logos.start(boothId, userId, contentType, byteSize);
    }

    @Operation(summary = "업로드 완료 — 실제 바이트 검증",
            description = """
                    올라온 파일을 서버가 읽어 검증하고 상태를 닫는다.

                    **검증 실패도 `200` 이다** — 응답의 `status` 가 `FAILED` 이고 `failureRule` 이 사유다
                    (`MIME_NOT_ALLOWED` · `SIZE_EXCEEDED` · `DIMENSION_EXCEEDED` · `DECODE_FAILED` ·
                    `UPLOAD_MISSING` · `GRANT_EXPIRED`). 실패한 자리는 되살아나지 않는다 — 재시도는
                    새 업로드다.

                    성공하면 `status` 가 `READY` 이고 `url` 이 나온다. **그 값을 기존
                    `PATCH /api/v1/projects/{projectId}` 의 `thumbnailUrl` 에 그대로 넣으면 저장된다** —
                    새 저장 계약은 없다.

                    같은 `logoId` 로 다시 불러도 안전하다(멱등) — 처음 판정을 그대로 돌려준다.

                    **아직 어느 프로젝트에도 저장하지 않은 로고는 무한정 남지 않는다.** 24시간 동안
                    참조되지 않으면 정리되고, 저장했다가 다른 이미지로 바꾼 옛 로고도 잠시 뒤 정리된다
                    (되돌리면 정리 대상에서 빠진다).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증 결과 — `READY` 또는 `FAILED`"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` 또는 `BOOTH_EDITOR_FORBIDDEN`"),
            @ApiResponse(responseCode = "404", description = "`PROJECT_LOGO_NOT_FOUND` · `BOOTH_NOT_FOUND`"),
            @ApiResponse(responseCode = "409", description = "`BOOTH_LEASE_EXPIRED`"),
            @ApiResponse(responseCode = "503", description = "`STORAGE_UNAVAILABLE` — 저장소가 답하지 않는다. 다시 부르면 된다")})
    @PostMapping("/{logoId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    public ProjectLogoService.LogoView complete(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable Long boothId,
                                                @Parameter(description = "시작에서 받은 식별자")
                                                @PathVariable String logoId) {
        Long userId = MemberPrincipal.requireMemberId(jwt);
        return logos.complete(boothId, logoId, userId);
    }

    /**
     * 이미지 바이트.
     *
     * <p><b>인증 없이도 열린다</b> — 방문자의 {@code <img>} 가 이 경로로 들어온다. 열린 것은 경로이고
     * 판정은 서비스가 한다: 게시된 프로젝트가 이 로고를 참조하고 임대가 유효하면 누구나, 그 밖의
     * 로고는 그 부스 편집자만, 나머지는 404 다.
     */
    @Operation(summary = "이미지 내려받기 — 방문자 공개",
            description = """
                    이미지 바이트를 그대로 내려준다. `<img src="...">` 에 그대로 쓰면 된다.

                    **게시된 프로젝트가 가리키는 로고는 로그인 없이 보인다.** 아직 저장하지 않았거나
                    미게시 상태인 로고는 **그 부스 편집자만** 볼 수 있다 — 업로드 직후 화면을 새로
                    열어도 자기 이미지가 보이는 이유가 이것이다. 그 밖에는 `404` 다.

                    `Content-Type` 은 **서버가 검증으로 확정한 값**이다 — 업로드가 선언한 값을 되돌려
                    주지 않는다. `X-Content-Type-Options: nosniff` 가 함께 나간다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 바이트"),
            @ApiResponse(responseCode = "404", description = "`PROJECT_LOGO_NOT_FOUND` — 없거나, 볼 권한이 없다"),
            @ApiResponse(responseCode = "503", description = "`STORAGE_UNAVAILABLE`")})
    @GetMapping("/{logoId}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable Long boothId,
                                          @PathVariable String logoId) {
        Long userId = MemberPrincipal.optionalMemberId(jwt);
        ProjectLogoService.LogoContent content = logos.content(boothId, logoId, userId);
        // 공개 로고는 방문자가 반복해서 읽으므로 캐시를 허용하고, 아직 공개되지 않은 것은 private 다 —
        // 공용 캐시에 올라가면 편집 중인 이미지가 권한 검사 밖에서 제공된다.
        CacheControl cacheControl = content.publiclyVisible()
                ? CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic()
                : CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(cacheControl)
                .header("X-Content-Type-Options", "nosniff")
                .body(content.content());
    }

    /**
     * @param contentType 올릴 파일의 MIME. 서명에 박히므로 {@code PUT} 이 같은 값을 보내야 한다
     * @param byteSize    올릴 파일의 바이트 수. 선언값이고, 실제 검증은 완료에서 한다
     */
    // 스키마 이름을 명시한다. 이름을 비워 두면 중첩 클래스 단순명(StartRequest)이 쓰이고, 게임 Asset
    // 업로드의 같은 이름 DTO 와 충돌해 나중에 등록된 쪽이 앞의 것을 덮는다 — 한 endpoint 의 요청
    // 본문이 남의 필드로 문서화되는 그 증상이다 (GitLab #172, S15P21A604-502 에서 밟았고
    // OpenApiSchemaNameTest 가 그때 생겼다).
    @Schema(name = "ProjectLogoStartRequest")
    public record StartRequest(
            @Schema(description = "PNG · JPEG · GIF · WebP", example = "image/png") String contentType,
            @Schema(description = "파일 크기(바이트). 5MB 이하", example = "204800") Long byteSize) {
    }
}
