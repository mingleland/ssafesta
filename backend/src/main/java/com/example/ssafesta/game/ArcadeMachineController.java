package com.example.ssafesta.game;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Arcade machine resolution — the world's game machines (S15P21A604-602, GitLab #56 안 1, #135).
 *
 * <p>Flat path, not under {@code /games}: the caller has a machine and is asking which game it is.
 * Nesting it under the game would require knowing the answer to ask the question.
 */
@Tag(name = "Arcade", description = "월드 오락기 — machineId 로 실행할 게임을 해석한다")
@RestController
@RequestMapping("/api/v1/arcade-machines")
public class ArcadeMachineController {

    private final ArcadeMachineResolveService resolver;

    public ArcadeMachineController(ArcadeMachineResolveService resolver) {
        this.resolver = resolver;
    }

    @Operation(summary = "오락기 목록 — 씬에 설치된 기계와 실행 가능 여부",
            description = """
                    바인딩된 기계만 `machineId` 오름차순으로 돌려준다. Unity 는 자기 씬의 기계 id 와
                    이 목록을 비교해 빈 자리를 판단한다. 삭제된 게임의 기계는 목록에서 빠지고,
                    비공개·미게시 게임은 `playable: false` 와 이유를 유지하되 `title` 은 `null` 이다 —
                    이 경로는 토큰 없이 열려 있어서 못 켜는 게임의 제목까지 줄 이유가 없다.

                    게임에는 썸네일 모델이 없으므로 `thumbnailUrl` 은 응답에 포함하지 않는다.
                    공개 상태가 바뀌면 바로 반영되어야 하므로 `Cache-Control: no-store` 다.
                    """)
    @ApiResponse(responseCode = "200", description = "바인딩된 오락기 목록")
    @GetMapping
    public ResponseEntity<List<ArcadeMachineResolveService.ListedView>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(resolver.resolveAll());
    }

    @Operation(summary = "오락기 해석 — 이 기계가 어떤 게임을 실행하는가",
            description = """
                    Unity 가 씬에 놓인 오락기의 canonical `machineId` 를 FE 로 보내면, FE 가 이 경로로
                    어떤 게임인지 묻는다. **Unity 는 `gameId` 를 모른다** — 큐레이션이 바뀌어도 Unity
                    빌드가 바뀌지 않게 한 분담이다 (spec 019 FR-017).

                    **토큰이 없어도 호출된다.** 게시된 게임을 플레이하는 것은 게스트의 몫이기도 하다
                    (FR-023).

                    **실행할 수 없는 상태도 `200` 이다.** 오락기는 월드 고정물이라 뒤에 걸린 게임이
                    비공개가 됐다고 월드를 끊지 않는다 — `playable: false` 와 `unavailableReason` 으로
                    안내만 한다 (FR-020). 어휘는 게임 공개 상태 3종뿐이다:
                    `GAME_NOT_PUBLISHED`(게시본 없음) · `GAME_NOT_PUBLIC`(비공개) · `GAME_DELETED`(삭제됨).
                    이 기계들은 임대 부스가 아니라 월드 고정물이므로 응답에 `boothId` 가 없고
                    `BOOTH_LEASE_EXPIRED` 계열도 나오지 않는다.

                    `publishedVersion` 은 `playable` 이 `true` 일 때만 값이 있다.

                    **`Cache-Control: no-store` 다.** 공개 상태는 매 요청 서버가 판정한다 — 캐시된
                    응답은 방금 비공개로 바꾼 게임을 계속 열어 준다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "해석 결과. `playable` 이 `false` 면 `unavailableReason` 이 이유다"),
            @ApiResponse(responseCode = "404",
                    description = "`MACHINE_NOT_FOUND` — 그런 `machineId` 로 등록된 오락기가 없다")})
    @GetMapping("/{machineId}")
    public ResponseEntity<ArcadeMachineResolveService.ResolvedView> resolve(
            @Parameter(description = "씬이 정한 오락기 canonical id", example = "plaza-arcade-01")
            @PathVariable String machineId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(resolver.resolve(machineId));
    }
}
