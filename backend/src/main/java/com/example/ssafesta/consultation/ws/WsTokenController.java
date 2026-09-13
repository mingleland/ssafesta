package com.example.ssafesta.consultation.ws;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * STOMP 연결용 단기 토큰 발급 (spec 011 FR-019·FR-020, contracts §B).
 *
 * <p><b>경로가 둘이고 정본은 {@code /api/v1/realtime/ws-token} 이다.</b> 이 토큰은 상담만의 것이
 * 아니게 됐다 — 같은 소켓이 월드 채팅도 나른다(S15P21A604-687). 상담 경로는 이미 FE 에 통보한
 * 계약이라 남겨 두고, 둘이 같은 로직을 부른다.
 */
@RestController
@RequestMapping(path = {"/api/v1/realtime/ws-token", "/api/v1/consultation/ws-token"})
@Tag(name = "Realtime")
public class WsTokenController {

    private static final String MEMBER_ONLY = "회원 계정만 실시간 채널에 연결할 수 있습니다.";

    private final WsTokenService tokens;

    public WsTokenController(WsTokenService tokens) {
        this.tokens = tokens;
    }

    @Operation(summary = "실시간 채널 WS Token 발급",
            description = """
                    STOMP 연결에만 쓰는 **5분짜리** 토큰이다 (C-14).

                    `wss://<host>/ws` 로 연결하면서 STOMP `CONNECT` 프레임의
                    `Authorization: Bearer <token>` 헤더에 싣는다. 한 소켓이 상담 알림과 월드
                    채팅을 함께 나른다 — `wss://<host>/ws/consultation` 도 같은 것으로 남겨 두었다.

                    **URL query 로 넘기지 않는다** (FR-019, 헌법 13조) — 그 자리에 실린 토큰은
                    접속 로그와 referrer 에 남는다. Access Token 을 그대로 쓰는 것도 허용하지
                    않는다.

                    **연결 성립 후에는 만료가 연결을 끊지 않는다** (FR-020). 이 토큰은 연결을
                    여는 열쇠이지 세션의 수명이 아니다 — 끊긴 뒤 재연결할 때 새로 발급받는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`token` 과 `expiresInSeconds`(300)"),
            @ApiResponse(responseCode = "403", description = "`MEMBER_ONLY` — 게스트는 실시간 채널에 연결할 수 없다")})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public WsTokenService.Issued issue(@AuthenticationPrincipal Jwt jwt) {
        return tokens.issue(MemberPrincipal.requireMemberId(jwt, MEMBER_ONLY));
    }
}
