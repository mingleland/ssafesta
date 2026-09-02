package com.example.ssafesta.ai;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document upload for an AI agent (docs/08 §7, spec 007 US2).
 *
 * <p>Two calls around an upload that does not pass through here: {@code upload-url} hands out a
 * presigned {@code PUT} the browser uses directly, and {@code complete} is how the client says the
 * bytes landed. Listing, deleting and handing the document to FastAPI are separate issues
 * (S15P21A604-174, -175).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI Document")
public class AiDocumentController {

    /** Guests own no booth, so they can own no document (헌법 12조). */
    private static final String MEMBER_ONLY = "회원 계정만 문서를 올릴 수 있습니다.";

    private final AiDocumentService documents;

    public AiDocumentController(AiDocumentService documents) {
        this.documents = documents;
    }

    /**
     * 200 for both outcomes. A duplicate is not an error — the file is already registered, which is
     * what the caller wanted (FR-019c), and the response omits {@code uploadUrl} entirely.
     */
    @Operation(summary = "문서 업로드 URL 발급 — 1단계",
            description = """
                    **파일은 이 서버를 거치지 않는다.** 여기서 받은 `uploadUrl` 로 브라우저가 저장소에 직접
                    `PUT` 하고, 끝나면 2단계(`POST /api/v1/documents/{documentId}/complete`)를 호출한다.

                    호출 순서

                    1. 이 endpoint 에 파일 이름·MIME·크기·SHA-256 을 보낸다
                    2. 응답의 `uploadUrl` 로 파일 바이트를 `PUT` 한다 (헤더를 임의로 더하지 않는다)
                    3. `complete` 를 호출해 업로드가 끝났다고 알린다

                    **같은 파일을 다시 올리면 `duplicate: true` 이고 `uploadUrl` 이 없다.** 오류가 아니다 —
                    같은 SHA-256 의 문서가 이미 등록돼 있으니 호출자가 원한 상태는 이미 이뤄졌다 (FR-019c).
                    이때는 2단계도 필요 없다.

                    **URL 에는 유효 시간이 있다.** 만료 후 업로드하면 2단계가 `410 DOCUMENT_UPLOAD_GONE` 이므로
                    1단계부터 다시 한다. 같은 문서에 대해 1단계를 다시 부르면 아직 완료되지 않은 문서에는
                    새 URL 이 재발급된다.

                    선언한 크기·형식은 **거절용으로만** 쓰인다. 실제 저장된 객체는 2단계에서 다시 검증된다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발급 성공(`duplicate: false`, `uploadUrl` 있음) 또는 중복(`duplicate: true`, `uploadUrl` 없음)"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 파일 이름·MIME·크기·해시 형식 위반. 허용 형식과 상한을 넘은 경우도 여기다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`AGENT_NOT_FOUND` — 그런 AI 직원이 없다"),
            @ApiResponse(responseCode = "409", description = "`DOCUMENT_LIMIT_EXCEEDED`(문서 수·총량 상한 초과) 또는 `BOOTH_LEASE_EXPIRED`"),
            @ApiResponse(responseCode = "503", description = "`STORAGE_UNAVAILABLE` — 저장소를 쓸 수 없다. 잠시 뒤 다시 시도한다")})
    @PostMapping("/agents/{agentId}/documents/upload-url")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.UploadGrantView uploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "문서를 붙일 AI 직원", example = "78")
            @PathVariable Long agentId,
            @RequestBody(required = false) AiDocumentService.UploadCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.issueUploadUrl(agentId, userId, command);
    }

    /** No request body: everything needed is on the row, and the object is verified against it. */
    @Operation(summary = "문서 업로드 완료 — 2단계",
            description = """
                    저장소에 올린 파일이 실제로 있는지 서버가 확인하고 문서를 처리 대기로 넘긴다.
                    **요청 본문이 없다** — 필요한 정보는 1단계에서 이미 서버에 있고, 저장된 객체를 그 정보와 대조한다.

                    성공하면 `processingStatus` 가 `QUEUED` 다. 이것은 **업로드가 끝났다는 뜻이고 RAG 준비가 끝난
                    것은 아니다** — 처리 완료는 별도 상태 조회로 확인한다.

                    **여러 번 불러도 안전하다.** 이미 완료된 문서는 같은 응답을 그대로 돌려준다 (멱등). 타임아웃 뒤
                    재시도가 실패로 보이지 않는다.

                    객체가 없거나 크기가 1단계에 선언한 값과 다르면 `409 DOCUMENT_UPLOAD_INCOMPLETE` 다 —
                    업로드가 중간에 끊겼거나 다른 파일이 올라간 경우이므로 1단계부터 다시 한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "완료 처리됨. `processingStatus: QUEUED`"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND` — 그런 문서가 없다. 권한 검사보다 먼저 판정된다"),
            @ApiResponse(responseCode = "409", description = "`DOCUMENT_UPLOAD_INCOMPLETE` — 객체가 없거나 선언한 크기와 다르다. 또는 완료할 수 있는 상태가 아니다"),
            @ApiResponse(responseCode = "410", description = "`DOCUMENT_UPLOAD_GONE` — 업로드 유효 시간이 지났다. 1단계부터 다시 한다"),
            @ApiResponse(responseCode = "503", description = "`STORAGE_UNAVAILABLE` — 저장소 확인에 실패했다")})
    @PostMapping("/documents/{documentId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.CompleteView complete(@AuthenticationPrincipal Jwt jwt,
                                                   @Parameter(description = "1단계 응답의 `documentId`", example = "1201")
                                                   @PathVariable Long documentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.complete(documentId, userId);
    }
}
