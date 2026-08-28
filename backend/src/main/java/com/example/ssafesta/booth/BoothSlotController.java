package com.example.ssafesta.booth;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.wallet.InsufficientCoinException;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Slot listing and leasing (spec 004 contracts/lease-api.md). */
@RestController
@RequestMapping("/api/v1/booth-slots")
public class BoothSlotController {

    private final BoothQueryService queries;
    private final BoothLeaseService leases;
    private final BoothLayoutQueryService layouts;

    public BoothSlotController(BoothQueryService queries, BoothLeaseService leases,
                               BoothLayoutQueryService layouts) {
        this.queries = queries;
        this.leases = leases;
        this.layouts = layouts;
    }

    /** Open to guests: browsing the world is what a guest session is for (헌법 12조). */
    @GetMapping
    public List<BoothQueryService.SlotView> slots(@AuthenticationPrincipal Jwt jwt) {
        return queries.listSlots(BoothPrincipal.optionalMemberId(jwt));
    }

    /**
     * The published layout of whatever booth currently holds this room (spec 005 contract §11,
     * #62). Unauthenticated, exactly like the booth-keyed path — every visitor reads it.
     *
     * <p>Unity calls this one because its anchors are rooms; the editor calls
     * {@code /booths/{boothId}/layouts/published} because it edits a booth. Same resource, two
     * natural keys, identical body.
     */
    @GetMapping("/{slotId}/layouts/published")
    public BoothLayoutQueryService.PublishedView published(@PathVariable Long slotId) {
        try {
            return layouts.findPublishedBySlot(slotId);
        } catch (SlotNotFoundException exception) {
            // Not an ApiException — 004 threw it before the error envelope existed, and the two
            // other call sites translate it here as well rather than in a handler.
            throw new ApiException(ErrorCode.BOOTH_SLOT_NOT_FOUND);
        }
    }

    @PostMapping("/{slotId}/leases")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<LeaseResponse> lease(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable Long slotId,
                                               @RequestBody(required = false) LeaseRequest request) {
        Long userId = BoothPrincipal.requireMemberId(jwt);
        int durationDays = request == null || request.durationDays() == null ? 1 : request.durationDays();
        try {
            BoothLeaseService.LeaseOutcome outcome = leases.lease(userId, slotId, durationDays);
            HttpStatus status = outcome.alreadyHeld() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(LeaseResponse.of(outcome));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, exception.getMessage());
        } catch (SlotNotFoundException exception) {
            throw new ApiException(ErrorCode.BOOTH_SLOT_NOT_FOUND);
        } catch (SlotNotRentableException exception) {
            throw new ApiException(ErrorCode.BOOTH_SLOT_NOT_RENTABLE);
        } catch (SlotAlreadyLeasedException exception) {
            throw new ApiException(ErrorCode.BOOTH_SLOT_ALREADY_LEASED);
        } catch (ActiveLeaseLimitException exception) {
            throw new ApiException(ErrorCode.ACTIVE_LEASE_LIMIT);
        } catch (InsufficientCoinException exception) {
            // Surfaced with the concrete shortfall — a silent fallback would leave the user
            // guessing why the booth was not rented (spec 003 FR-009).
            throw new ApiException(ErrorCode.INSUFFICIENT_COIN,
                    "코인이 부족합니다. 필요: " + exception.getRequired() + ", 잔액: " + exception.getBalance());
        }
    }

    public record LeaseRequest(Integer durationDays) { }

    public record LeaseResponse(Long leaseId, Long boothId, Long slotId, Instant startsAt, Instant endsAt,
                                long remainingSeconds, int chargedCoin, int balanceAfter) {

        static LeaseResponse of(BoothLeaseService.LeaseOutcome outcome) {
            BoothLease lease = outcome.lease();
            return new LeaseResponse(lease.getId(), lease.getBoothId(), lease.getSlotId(), lease.getStartsAt(),
                    lease.getEndsAt(), lease.remainingSecondsAt(Instant.now()), lease.getChargedCoin(),
                    outcome.balanceAfter());
        }
    }
}
