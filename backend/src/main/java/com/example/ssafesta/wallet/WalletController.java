package com.example.ssafesta.wallet;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    public WalletBalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
        Long userId = memberId(jwt);
        Wallet wallet = findWallet(userId);
        return new WalletBalanceResponse(userId, wallet.getBalance(), wallet.getUpdatedAt());
    }

    @GetMapping("/transactions")
    public TransactionPageResponse transactions(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
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
    private ResponseStatusException missingWallet(Long userId, WalletNotFoundException exception) {
        log.error("회원에게 지갑이 없습니다 — 가입 트랜잭션을 확인해야 합니다. userId={}", userId, exception);
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다.");
    }

    private Long memberId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access Token이 필요합니다.");
        }
        if (!"MEMBER".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회원 계정만 코인 지갑을 사용할 수 있습니다.");
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 회원 토큰입니다.");
        }
    }

    public record WalletBalanceResponse(Long userId, int balance, Instant updatedAt) { }

    public record TransactionPageResponse(List<CoinLedgerEntryView> content, int page, int size,
                                          long totalElements, int totalPages) { }
}
