package com.example.ssafesta.booth;

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
import org.springframework.web.server.ResponseStatusException;

/** Slot listing and leasing (spec 004 contracts/lease-api.md). */
@RestController
@RequestMapping("/api/v1/booth-slots")
public class BoothSlotController {

    private final BoothQueryService queries;
    private final BoothLeaseService leases;

    public BoothSlotController(BoothQueryService queries, BoothLeaseService leases) {
        this.queries = queries;
        this.leases = leases;
    }

    /** Open to guests: browsing the world is what a guest session is for (헌법 12조). */
    @GetMapping
    public List<BoothQueryService.SlotView> slots(@AuthenticationPrincipal Jwt jwt) {
        return queries.listSlots(BoothPrincipal.optionalMemberId(jwt));
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SlotNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (SlotNotRentableException exception) {
            throw conflict("BOOTH_SLOT_NOT_RENTABLE", "임대할 수 없는 슬롯입니다.");
        } catch (SlotAlreadyLeasedException exception) {
            throw conflict("BOOTH_SLOT_ALREADY_LEASED", "이미 임대 중인 슬롯입니다.");
        } catch (ActiveLeaseLimitException exception) {
            throw conflict("ACTIVE_LEASE_LIMIT", "이미 임대 중인 부스가 있습니다. 만료 후 다시 임대할 수 있습니다.");
        } catch (InsufficientCoinException exception) {
            // Surfaced with the concrete shortfall — a silent fallback would leave the user
            // guessing why the booth was not rented (spec 003 FR-009).
            throw conflict("INSUFFICIENT_COIN",
                    "코인이 부족합니다. 필요: " + exception.getRequired() + ", 잔액: " + exception.getBalance());
        }
    }

    private ResponseStatusException conflict(String code, String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code + ": " + message);
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
