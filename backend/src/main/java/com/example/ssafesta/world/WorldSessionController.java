package com.example.ssafesta.world;

import com.example.ssafesta.common.ApiErrorDetail;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "World Session")
public class WorldSessionController {

    private final WorldSessionService sessions;

    public WorldSessionController(WorldSessionService sessions) {
        this.sessions = sessions;
    }

    @Operation(summary = "월드 접속 정보 발급 — Unity 가 연결 직전에 부른다",
            description = """
                    Unity 클라이언트가 게임 서버에 붙기 전에 **접속 주소와 1회용 입장 토큰**을 받아 간다.
                    회원과 게스트 **둘 다** 호출할 수 있다 — 월드를 구경하는 것은 게스트의 몫이기도 하다.

                    **`GET` 이 아니라 `POST` 인 이유** — 호출마다 새 입장 토큰을 만든다. 멱등하지 않으므로
                    캐시하거나 미리 받아두면 안 된다. 입장 토큰은 120초 뒤 스스로 만료된다.

                    `endpoint` 는 문자열이 아니라 **객체**(`scheme`·`host`·`port`)다. 클라이언트가 URL 을
                    문자열로 조립하다 틀리는 일을 막는다.

                    세션을 닫는 API 는 **없다.** 서버에 저장하는 상태가 없어 지울 것이 없고, 연결이 끊기면
                    게임 서버가 정리하며 입장 토큰은 저절로 만료된다.

                    요청 본문은 생략해도 된다. `worldId` 를 보내면 현재 지원하는 구역인지 검사하고,
                    다른 값이면 조용히 무시하지 않고 `400` 으로 거부한다 — 없는 층을 요청한 클라이언트는
                    다른 곳으로 보내지는 대신 그 사실을 알아야 한다 (FR-012).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "접속 주소와 120초 1회용 입장 토큰"),
            @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` — 지원하지 않는 `worldId` 다"),
            @ApiResponse(responseCode = "401", description = "`USER_NOT_FOUND` — 회원 토큰인데 그 회원의 행이 없다"),
            @ApiResponse(responseCode = "500", description = "서버에 입장 토큰 서명 키가 주입되지 않았다. 배포 설정 문제다")})
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
