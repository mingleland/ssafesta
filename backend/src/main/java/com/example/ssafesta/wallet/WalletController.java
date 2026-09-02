package com.example.ssafesta.wallet;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only wallet API (spec 003 contracts/wallet-api.md).
 *
 * <p>There is deliberately no endpoint that changes coins: a client must never be able to name the
 * amount or the target of a movement (헌법 2·16조, spec 003 FR-010/FR-011). Spending happens inside
 * the server-side logic of the feature that charges for it, through {@link WalletService}.
 */
@RestController
@RequestMapping("/api/v1/wallets/me")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Wallet")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @Operation(summary = "내 코인 잔액 조회",
            description = """
                    로그인한 회원의 코인 잔액을 돌려준다.

                    **당일 첫 인증 요청에 일일 지급이 함께 일어난다.** 그날(KST 기준) 처음 인증된 요청을 보내면 서버가
                    일일 코인을 먼저 지급하므로, 이 endpoint 를 그날 처음 호출했다면 응답 잔액에 그 지급이 이미 포함돼 있다.
                    같은 요청을 반복해도 값이 변하지 않는다 — 변한다면 지급이 하루에 두 번 일어난 것이다 (spec 003 FR-003).
                    신규 가입 계정의 첫 조회는 가입 지급 + 당일 지급을 합한 값이다.

                    **코인을 넣거나 빼는 API 는 없다.** 클라이언트가 금액이나 대상을 지정할 수 있으면 안 되기 때문이며
                    (헌법 2·16조), 차감은 부스 임대처럼 값을 받는 기능의 서버 로직 안에서만 일어난다.

                    게스트에게는 지갑이 없다 — `403` 이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "잔액과 마지막 변경 시각"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다. 게스트에게는 지갑이 없다"),
            @ApiResponse(responseCode = "404", description = "`WALLET_NOT_FOUND` — 회원인데 지갑 행이 없다. 정상 상태가 아니며 서버 로그에 근거가 남는다")})
    @GetMapping
    public WalletBalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
        Long userId = memberId(jwt);
        Wallet wallet = findWallet(userId);
        return new WalletBalanceResponse(userId, wallet.getBalance(), wallet.getUpdatedAt());
    }

    @Operation(summary = "내 코인 거래 내역 조회 (최신순 페이지)",
            description = """
                    지급과 차감을 한 목록으로 최신순으로 돌려준다. 이 목록이 **원장**이고 잔액의 근거다 —
                    `content` 의 `amount` 합계는 항상 `GET /api/v1/wallets/me` 의 잔액과 같아야 한다.

                    `amount` 는 **지급이 양수, 차감이 음수**다. 부호 없이 크기만 쓰면 어느 쪽인지 알 수 없다.
                    `reasonType` 으로 사유를 가른다 — 가입 지급(`INITIAL_GRANT`), 일일 지급(`DAILY_GRANT`),
                    부스 임대(`LEASE_PAYMENT`) 등.

                    페이지는 `page`(0부터)·`size`(1~100)이고 범위를 벗어나면 `400` 이다. 가장 오래된 항목은
                    마지막 페이지의 끝에 있는 가입 지급이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최신순 한 페이지와 전체 개수"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — `page` 가 음수이거나 `size` 가 1~100 밖이다"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트 토큰이다"),
            @ApiResponse(responseCode = "404", description = "`WALLET_NOT_FOUND` — 회원인데 지갑 행이 없다")})
    @GetMapping("/transactions")
    public TransactionPageResponse transactions(@AuthenticationPrincipal Jwt jwt,
                                                @Parameter(description = "0부터 시작하는 페이지 번호", example = "0")
                                                @RequestParam(defaultValue = "0") int page,
                                                @Parameter(description = "한 페이지 크기. 1~100", example = "20")
                                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        Long userId = memberId(jwt);
        Page<CoinLedgerEntryView> entries = findHistory(userId, PageRequest.of(page, size));
        return new TransactionPageResponse(entries.getContent(), entries.getNumber(), entries.getSize(),
                entries.getTotalElements(), entries.getTotalPages());
    }

    private Wallet findWallet(Long userId) {
        try {
            return wallets.requireWallet(userId);
        } catch (WalletNotFoundException exception) {
            throw missingWallet(userId, exception);
        }
    }

    private Page<CoinLedgerEntryView> findHistory(Long userId, PageRequest pageRequest) {
        try {
            return wallets.history(userId, pageRequest);
        } catch (WalletNotFoundException exception) {
            throw missingWallet(userId, exception);
        }
    }

    /**
     * A member without a wallet is a broken state, not an expected 404: the wallet is created in
     * the member-creation transaction. Report it, and leave a trace to investigate with.
     */
    private ApiException missingWallet(Long userId, WalletNotFoundException exception) {
        log.error("회원에게 지갑이 없습니다 — 가입 트랜잭션을 확인해야 합니다. userId={}", userId, exception);
        return new ApiException(ErrorCode.WALLET_NOT_FOUND);
    }

    private Long memberId(Jwt jwt) {
        return MemberPrincipal.requireMemberId(jwt, "회원 계정만 코인 지갑을 사용할 수 있습니다.");
    }

    public record WalletBalanceResponse(
            @Schema(description = "지갑 주인의 회원 식별자", example = "12") Long userId,
            @Schema(description = "현재 코인 잔액. 정수이며 원장 합계와 일치한다", example = "250") int balance,
            @Schema(description = "잔액이 마지막으로 바뀐 시각(UTC)", example = "2026-09-02T05:00:00Z") Instant updatedAt) { }

    @Schema(description = "최신순 한 페이지")
    public record TransactionPageResponse(
            @Schema(description = "이 페이지의 거래 목록. 최신순이다") List<CoinLedgerEntryView> content,
            @Schema(description = "현재 페이지 번호(0부터)", example = "0") int page,
            @Schema(description = "요청한 페이지 크기", example = "20") int size,
            @Schema(description = "전체 거래 개수", example = "5") long totalElements,
            @Schema(description = "전체 페이지 수", example = "1") int totalPages) { }
}
