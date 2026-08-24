package com.example.ssafesta.world;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entry point Unity calls before connecting (spec 002 US2, contract
 * {@code world-session.openapi.yaml}).
 *
 * <p>{@code POST} and not {@code GET}: each call mints a distinct one-time grant, so it is not
 * idempotent and must not be cached or prefetched. Jira -84's title says GET; `docs/08` §16,
 * `docs/21` ADR:210 and the OpenAPI contract all say POST, and the issue text is the older of the
 * two (research R-01).
 *
 * <p>There is no {@code DELETE /world-sessions/{id}}. `docs/08` sketched one, but with nothing stored
 * server-side there is no state to delete — a disconnect is cleaned up by the game server and the
 * grant expires on its own in 120 seconds.
 */
@RestController
@RequestMapping("/api/v1/world-sessions")
@SecurityRequirement(name = "bearerAuth")
public class WorldSessionController {

    private final WorldSessionService sessions;

    public WorldSessionController(WorldSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    public WorldSessionService.WorldSessionResponse open(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestBody(required = false) WorldSessionRequest request) {
        if (request != null) {
            requireKnownWorld(request.worldId());
        }
        return sessions.open(jwt);
    }

    /**
     * The floor parameter is accepted and checked even though exactly one value is legal.
     *
     * <p>헌법 9조 requires session issuance to take the destination floor from the first MVP, and
     * Issue #31 reaffirmed it for BE after the world became a single floor: the slot stays because
     * removing it is itself a contract change, while a parameter pinned to one value costs nobody
     * anything. Silently ignoring a wrong value would be worse than refusing it — a client asking
     * for a floor that does not exist should hear so, not be sent somewhere else (FR-012).
     */
    private static void requireKnownWorld(String worldId) {
        if (worldId == null || WorldProperties.WORLD_ID.equals(worldId)) {
            return;
        }
        String message = "입장할 수 없는 구역입니다. (지원: " + WorldProperties.WORLD_ID + ")";
        throw new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(ApiErrorDetail.field("worldId", message)), null);
    }

    /**
     * @param worldId          destination floor. Optional — absent means the only world there is
     * @param preferredPartyId accepted and ignored: MVP is a single channel, so there is no
     *                         allocation to influence (spec 002 C-02, `docs/08` §16). Kept in the
     *                         shape so the field does not have to be re-added later
     */
    public record WorldSessionRequest(String worldId, String preferredPartyId) { }
}
