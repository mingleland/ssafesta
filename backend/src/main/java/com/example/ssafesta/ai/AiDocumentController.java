package com.example.ssafesta.ai;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
    @PostMapping("/agents/{agentId}/documents/upload-url")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.UploadGrantView uploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long agentId,
            @RequestBody(required = false) AiDocumentService.UploadCommand command) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.issueUploadUrl(agentId, userId, command);
    }

    /** No request body: everything needed is on the row, and the object is verified against it. */
    @PostMapping("/documents/{documentId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    public AiDocumentService.CompleteView complete(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable Long documentId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY);
        return documents.complete(documentId, userId);
    }
}
