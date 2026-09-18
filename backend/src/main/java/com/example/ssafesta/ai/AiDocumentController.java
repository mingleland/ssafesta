package com.example.ssafesta.ai;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document upload for an AI agent (docs/08 §7, spec 007 US2).
 *
 * <p>Two calls around an upload that does not pass through here: {@code upload-url} hands out a
 * presigned {@code PUT} the browser uses directly, and {@code complete} is how the client says the
 * bytes landed. {@code replacement} is the first of those two aimed at an existing {@code READY}
 * document (FR-019) — it answers with a grant for a <b>new</b> {@code documentId} and the client
 * finishes on the same {@code complete}. A fourth call lists what the agent has and where each
 * document is in processing, and a fifth deletes one (FR-012). Handing the document to FastAPI is
 * S15P21A604-175.
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
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(그 부스 편집 권한이 없다)"),
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

    /**
     * {@code PUT} because the request names the thing being replaced and repeating it converges on
     * the same state — a second call with the same file answers {@code duplicate: true} rather than
     * starting a second replacement.
     */
    @Operation(summary = "문서 수정본 교체 URL 발급 — 1단계",
            description = """
                    **준비 완료(`READY`) 문서를 새 파일로 갈아 끼운다.** 응답은 업로드 URL 발급과 같은 모양이고,
                    `documentId` 는 **새로 만들어진 문서**의 것이다. 이어서 그 id 로
                    `POST /api/v1/documents/{newDocumentId}/complete` 를 부른다 — 2단계는 일반 업로드와 같다.

                    | 언제 | 무엇이 바뀌는가 |
                    |---|---|
                    | 이 호출 | 새 문서가 `QUEUED` 로 생긴다. **원본은 `READY` 그대로 검색에 쓰인다** |
                    | 2단계 완료 | 새 문서가 처리 대기로 넘어간다. **원본은 아직 그대로다** |
                    | 새 문서가 `READY` 가 된 순간 | 원본이 `EXPIRED` + `replacedAt` 으로 물러난다 |

                    **처리에 실패하면 원본은 살아 있다.** 교체는 성공한 뒤에만 일어나므로, 파싱이 안 되는 파일을
                    올려도 AI 직원이 답할 근거가 사라지지 않는다.

                    **같은 파일을 다시 보내면** `duplicate: true` 다 — 대상 문서 자신과 같은 해시면 아무것도 만들지
                    않고, 이미 올라온 교체본과 같은 해시면 그 교체본의 `documentId` 를 돌려준다. 아직 업로드하지
                    않은 교체본에 같은 파일로 다시 부르면 **새 `uploadUrl` 이 재발급**된다.

                    **문서 상한(10개)을 다 쓴 AI 직원도 교체할 수 있다.** 교체본은 원본의 자리를 이어받으므로
                    논리 합계가 늘지 않는다 — 목록 응답의 `quota.usedCount` 가 그 수다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발급 성공(`duplicate: false`, `uploadUrl` 있음) 또는 중복(`duplicate: true`, `uploadUrl` 없음)"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 파일 이름·MIME·크기·해시 형식 위반. 진행 중인 교체본과 해시는 같은데 이름·형식·크기가 다른 경우도 여기다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND` — 그런 문서가 없다"),
            @ApiResponse(responseCode = "409", description = "`DOCUMENT_NOT_REPLACEABLE` 또는 `DOCUMENT_LIMIT_EXCEEDED` 또는 `BOOTH_LEASE_EXPIRED` — 차례로 대상이 준비 완료 상태가 아니거나·이미 진행 중인 교체가 있거나·같은 파일이 다른 문서로 등록돼 있는 경우, 총량 상한을 넘는 경우, 임대가 끝난 경우다"),
            @ApiResponse(responseCode = "503", description = "`STORAGE_UNAVAILABLE` — 저장소를 쓸 수 없다. 잠시 뒤 다시 시도한다")})
    @PutMapping("/documents/{documentId}/replacement")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.UploadGrantView replace(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "교체할 기존 문서", example = "153")
            @PathVariable Long documentId,
            @RequestBody(required = false) AiDocumentService.UploadCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.replace(documentId, userId, command);
    }

    @Operation(summary = "문서 목록·처리 상태 조회",
            description = """
                    이 AI 직원에 등록된 문서를 **모든 상태**로 돌려준다. 최근 발급 순이다.

                    처리는 비동기라 업로드 완료(2단계)가 곧 준비 완료는 아니다. 진행은 이 endpoint 를
                    다시 불러 확인한다.

                    | `status` | 뜻 |
                    |---|---|
                    | `QUEUED` | 접수됨. `uploadedAt` 이 `null` 이면 **아직 업로드가 안 끝난 것**이다 |
                    | `PROCESSING` | 처리 중 |
                    | `READY` | 준비 완료 — 검색에 쓰인다 |
                    | `FAILED` | 처리 실패 |
                    | `EXPIRED` | **업로드 만료** — 1시간 안에 업로드가 끝나지 않았다. 파일을 다시 올리면 된다 |
                    | `DISABLED` | 임대 만료로 꺼진 상태. 사용자가 되돌릴 수 없다 |

                    **`EXPIRED` 는 `replacedAt` 으로 갈린다.** 값이 없으면 위 표대로 업로드 만료라 다시 올리면
                    되고, **값이 있으면 수정본으로 교체돼 물러난 원본**이라 복구되지 않는다 — 화면에 "교체됨"
                    으로 적고 다시 올리기 안내를 띄우지 않는다. 이 행에 `complete` 를 부르면
                    `410 DOCUMENT_UPLOAD_GONE` 이다. 상태 값 자체는 여섯 그대로다.

                    **`quota` 는 상한과 쓴 양을 함께 준다.** 자리를 차지하는 것은 `QUEUED`·`PROCESSING`·`READY`
                    인 행뿐이고 `FAILED`·`EXPIRED`·`DISABLED` 는 목록에만 나온다(실패한 업로드가 슬롯을 잡으면
                    안 된다). 게다가 **교체 진행 중에는 교체본이 원본의 자리를 이어받으므로** 활성 상태인 행이
                    11개라도 `usedCount` 는 10이다 — "n/10" 의 n 은 `usedCount` 이지 `documents.length` 도,
                    활성 상태 행의 수도 아니다.

                    **AI 처리 서버가 죽어 있어도 이 조회는 답한다.** 모든 값이 이 서버의 문서 행에 있다.

                    임대가 만료된 부스도 소유자·Staff 는 조회할 수 있다. 문서가 `DISABLED` 로 바뀔 뿐
                    원본과 메타데이터는 보존된다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. 문서가 없으면 `documents` 가 빈 배열이다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`AGENT_NOT_FOUND` — 그런 AI 직원이 없다")})
    @GetMapping("/agents/{agentId}/documents")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.DocumentListView list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "문서를 조회할 AI 직원", example = "78")
            @PathVariable Long agentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.list(agentId, userId);
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

                    **수정본으로 교체돼 물러난 원본(`EXPIRED` + `replacedAt`)은 `410 DOCUMENT_UPLOAD_GONE` 이다.**
                    24시간 복구는 "바이트가 안 왔다" 는 만료에만 해당하고, 이미 새 버전이 자리를 넘겨받은 문서는
                    되돌릴 자리가 없다. 원본 파일이 저장소에 남아 있어도 결과는 같다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "완료 처리됨. `processingStatus: QUEUED`"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(그 부스 편집 권한이 없다)"),
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

    @Operation(summary = "문서 삭제",
            description = """
                    부스 편집자가 문서를 지운다 (FR-012, C-15). **되돌릴 수 없다** — 원본과 메타데이터가 즉시
                    삭제 대상이 되고, 삭제된 문서는 더 이상 AI 직원의 답변 근거로 쓰이지 않는다.

                    업로드가 끝나 처리 대기 중인 `QUEUED`와 `PROCESSING` 문서는 지금 지울 수 없다.
                    업로드 전 `QUEUED` 및 `READY`·`FAILED`·`EXPIRED`·`DISABLED`는 삭제할 수 있고,
                    임대가 끝난 부스의 보존 문서도 편집 권한이 있으면 삭제할 수 있다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제됨"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY`(게스트) 또는 `BOOTH_EDITOR_FORBIDDEN`(그 부스 편집 권한이 없다)"),
            @ApiResponse(responseCode = "404", description = "`DOCUMENT_NOT_FOUND` — 그런 문서가 없다"),
            @ApiResponse(responseCode = "409", description = "`DOCUMENT_NOT_DELETABLE` — 아직 처리 중이다")})
    @DeleteMapping("/documents/{documentId}")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt,
                       @Parameter(description = "삭제할 문서", example = "1201")
                       @PathVariable Long documentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        documents.delete(documentId, userId);
    }
}
