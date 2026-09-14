package com.example.ssafesta.consultation.ws;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
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
 *
 * <p><b>게스트도 받는다</b>(S15P21A604-727). 그 전에는 회원 전용이었는데, 그러면 부스 변경
 * 방송이 게스트에게 닿지 않아 게스트는 세션 내내 낡은 부스를 본다. 게스트가 받는 것은 <b>읽기
 * 전용</b> 연결이다 — 채팅 발신과 부스 대기열 구독은 회원만 할 수 있고, 그 판정은 주체가
 * 숫자인지로 한다.
 */
@RestController
@RequestMapping(path = {"/api/v1/realtime/ws-token", "/api/v1/consultation/ws-token"})
@Tag(name = "Realtime")
public class WsTokenController {

    private final WsTokenService tokens;

    public WsTokenController(WsTokenService tokens) {
        this.tokens = tokens;
    }

    @Operation(summary = "실시간 채널 WS Token 발급",
            description = """
                    STOMP 연결에만 쓰는 **5분짜리** 토큰이다 (C-14).

                    `wss://<host>/ws` 로 연결하면서 STOMP `CONNECT` 프레임의
                    `Authorization: Bearer <token>` 헤더에 싣는다. 한 소켓이 상담 알림과 월드
                    채팅, 부스 변경 방송을 함께 나른다 — `wss://<host>/ws/consultation` 도 같은
                    것으로 남겨 두었다.

                    **게스트도 발급받는다** (S15P21A604-727). 다만 게스트 연결은 읽기 전용이다 —
                    `/app/world/chat` 으로 보내면 `MEMBER_ONLY` 이고, 부스 대기열 토픽은 구독할 수
                    없다.

                    **URL query 로 넘기지 않는다** (FR-019, 헌법 13조) — 그 자리에 실린 토큰은
                    접속 로그와 referrer 에 남는다. Access Token 을 그대로 쓰는 것도 허용하지
                    않는다.

                    **연결 성립 후에는 만료가 연결을 끊지 않는다** (FR-020). 이 토큰은 연결을
                    여는 열쇠이지 세션의 수명이 아니다 — 끊긴 뒤 재연결할 때 새로 발급받는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "`token` 과 `expiresInSeconds`(300)"),
            @ApiResponse(responseCode = "401", description = "`UNAUTHORIZED` — 토큰이 없다")})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public WsTokenService.Issued issue(@AuthenticationPrincipal Jwt jwt) {
        return tokens.issue(subjectOf(jwt));
    }

    /**
     * 연결에 실을 주체 — 회원은 id 문자열, 게스트는 접속 토큰의 주체다.
     *
     * <p><b>게스트 주체가 숫자이면 거부한다.</b> 여기가 그 판정의 유일한 문지기다: 구독·발신
     * 게이트는 "주체가 숫자면 회원" 으로 읽으므로, 숫자 주체를 가진 게스트 토큰을 한 번이라도
     * 발급하면 그 연결이 남의 부스 대기열을 여는 회원 행세를 한다. 지금 게스트 주체는
     * {@code guest:<uuid>} 라 실제로 걸릴 일이 없고, 그래서 더욱 형식이 바뀌는 날 조용히
     * 무너지지 않도록 여기서 막아 둔다.
     */
    private static String subjectOf(Jwt jwt) {
        if (jwt == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Long memberId = MemberPrincipal.optionalMemberId(jwt);
        if (memberId != null) {
            return String.valueOf(memberId);
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.chars().allMatch(Character::isDigit)) {
            throw new ApiException(ErrorCode.INVALID_MEMBER_TOKEN);
        }
        return subject;
    }
}
