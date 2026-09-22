package com.example.ssafesta.game;

import com.example.ssafesta.common.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 오락기별 전 사용자 TOP 5 랭킹 (S15P21A604-963, GitLab #264).
 *
 * <p><b>경로가 {@code /api/v1} 밖이다.</b> #264 초안을 그대로 채택했다(2026-09-22). 그래서 이
 * prefix 는 {@code SecurityConfiguration} 의 CSRF 예외 목록과 공개 경로 목록에 <b>따로</b>
 * 올라간다 — {@code /api/v1/**} 규칙이 덮어 주지 않는다.
 *
 * <p>{@link ArcadeMachineController} 와 다른 경로다. 그쪽은 "이 기계가 무슨 게임인가" 를 묻고,
 * 여기는 "이 기계에서 누가 잘했나" 를 묻는다. 랭킹은 게임이 아니라 기계에 귀속되므로 두 질문의
 * 답이 서로를 필요로 하지 않는다.
 */
@Tag(name = "Arcade", description = "월드 오락기 — machineId 로 실행할 게임을 해석한다")
@RestController
@RequestMapping("/api/arcade/rankings")
public class ArcadeRankingController {

    private final ArcadeRankingService rankings;

    public ArcadeRankingController(ArcadeRankingService rankings) {
        this.rankings = rankings;
    }

    @Operation(summary = "점수 등록 — 내 최고점을 올린다",
            description = """
                    **표시 전용이다.** 서버는 인증 주체만 검증하고 점수 자체는 클라이언트 신고값을
                    그대로 기록한다 (spec 019 FR-021 의 2026-09-22 예외). 그 대가로 이 점수는
                    Coin·Reward·Inventory 어디에도 닿지 않는다.

                    **본문의 사용자 식별자는 받지 않는다.** 누구의 기록인지는 토큰이 정한다.

                    **같거나 낮은 점수는 오류가 아니다.** 기존 최고점을 그대로 두고 `updated: false`
                    로 답한다 — 클라이언트가 같은 결과를 다시 보낼 수 있어야 한다.

                    `score` 는 `0 ~ 100,000` 이고 벗어나면 `400 VALIDATION_FAILED` 다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "호출 뒤의 내 기록. `updated` 가 갱신 여부다"),
            @ApiResponse(responseCode = "404", description = "`MACHINE_NOT_FOUND` — 등록되지 않은 오락기")})
    @PostMapping("/{machineId}/scores")
    public ResponseEntity<ArcadeRankingService.SubmitResult> submit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "씬이 정한 오락기 canonical id", example = "arcade-01")
            @PathVariable String machineId,
            @Valid @RequestBody ScoreSubmission body) {
        Long userId = MemberPrincipal.requireMemberId(jwt, "회원 계정만 점수를 등록할 수 있습니다.");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(rankings.submit(machineId, userId, body.score()));
    }

    @Operation(summary = "TOP 5 — 이 오락기의 전 사용자 상위 기록",
            description = """
                    **토큰이 없어도 호출된다.** 게시된 게임 플레이가 게스트의 몫이기도 한데
                    (FR-023) 순위판만 못 볼 이유가 없다.

                    **랭킹은 게임기에 귀속된다.** 캐비닛에 걸린 게임이 바뀌어도 이전 게임에서 딴
                    점수가 같은 순위표에 남는다 (2026-09-22 결정).

                    정렬은 최고점 내림차순이고, 동점이면 먼저 달성한 기록이 위다.

                    `limit` 은 `1~5` 로 clamp 한다 — 범위 밖 숫자는 조용히 당겨진다. 기록이 없으면
                    빈 배열이며 404 가 아니다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상위 기록 (최대 5건)"),
            @ApiResponse(responseCode = "404", description = "`MACHINE_NOT_FOUND` — 등록되지 않은 오락기")})
    @GetMapping("/{machineId}")
    public ResponseEntity<List<ArcadeRankingService.RankEntry>> top(
            @Parameter(description = "씬이 정한 오락기 canonical id", example = "arcade-01")
            @PathVariable String machineId,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(rankings.top(machineId, limit));
    }

    @Operation(summary = "내 기록 — 이 오락기에서의 최고점과 순위",
            description = """
                    기록이 없어도 **`200` 이고 세 필드가 `null`** 이다. 이 경로의 `404` 는 "그런
                    오락기가 없다" 하나여야 클라이언트가 둘을 구별할 수 있다.

                    순위는 TOP 조회와 같은 정렬 규칙으로 센다 — 같은 점수·같은 시각이어도 두 응답이
                    어긋나지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "내 최고점과 순위. 기록이 없으면 전 필드 `null`"),
            @ApiResponse(responseCode = "404", description = "`MACHINE_NOT_FOUND` — 등록되지 않은 오락기")})
    @GetMapping("/{machineId}/me")
    public ResponseEntity<ArcadeRankingService.MyScoreView> me(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "씬이 정한 오락기 canonical id", example = "arcade-01")
            @PathVariable String machineId) {
        Long userId = MemberPrincipal.requireMemberId(jwt, "회원 계정만 랭킹 기록을 볼 수 있습니다.");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(rankings.me(machineId, userId));
    }

    /**
     * 점수 등록 본문.
     *
     * <p>{@code userId} 를 받지 않는다 — 클라이언트가 보낸 식별자는 신뢰하지 않는다는 요구(#264)를
     * 검증이 아니라 <b>형태</b>로 지킨다. 알 수 없는 필드는 무시된다.
     *
     * @param score {@code 0 ~ 100,000} (2026-09-22 확정, docs/26 결정 기록)
     */
    public record ScoreSubmission(
            @NotNull(message = "점수가 필요합니다.")
            @Min(value = 0, message = "점수는 0 이상이어야 합니다.")
            @Max(value = 100_000, message = "점수는 100000 이하여야 합니다.")
            Integer score) {
    }
}
