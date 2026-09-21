package com.example.ssafesta.game;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사용자가 자리를 잡을 수 있는 오락실 캐비닛 목록 (S15P21A604-942, GitLab #256).
 *
 * <p><b>조회에는 쓰이지 않는다.</b> {@code GET /arcade-machines} 는 바인딩된 기계만 돌려주고
 * {@code machineId} 의 정본은 여전히 Unity 씬이다 (spec 019 FR-017). 이 목록이 필요한 이유는
 * 하나뿐이다 — 자리 배정은 <b>없는 자리를 거절해야</b> 하는데, 바인딩 표만 보고는 {@code arcade-99}
 * 가 "아직 아무도 안 잡은 자리" 인지 "존재하지 않는 자리" 인지 구별할 수 없다. 그대로 두면 아무도
 * 갈 수 없는 자리에 게임이 걸린 채 한도만 먹는다.
 *
 * <p>광장 오락기({@code plaza-arcade-*})는 여기 없다. 그쪽은 운영자가 큐레이션하는 고정물이라
 * 사용자가 잡을 자리가 아니다.
 *
 * <p>슬롯머신의 {@code machine-ids} 와 같은 방식이다 — 기계가 늘면 이 목록만 고친다.
 *
 * @param machineIds 사용자가 고를 수 있는 캐비닛. 씬이 쓰는 값과 글자 그대로 같아야 한다
 * @param seatsPerUser 한 사람이 동시에 차지할 수 있는 캐비닛 수
 */
@ConfigurationProperties("app.arcade")
public record ArcadeProperties(List<String> machineIds, int seatsPerUser) {

    public ArcadeProperties {
        if (machineIds == null || machineIds.isEmpty()) {
            throw new IllegalStateException("app.arcade.machine-ids 가 비어 있습니다 — "
                    + "목록이 비면 아무도 자리를 잡을 수 없습니다.");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String machineId : machineIds) {
            if (machineId == null || machineId.isBlank()) {
                throw new IllegalStateException("app.arcade.machine-ids 에 빈 값이 있습니다.");
            }
            if (!seen.add(machineId)) {
                // 중복은 조용히 지나가면 "자리가 20개" 라는 셈만 틀린다. 씬과 대조할 때 드러나야 한다.
                throw new IllegalStateException(
                        "app.arcade.machine-ids 에 같은 값이 두 번 있습니다: " + machineId);
            }
        }
        machineIds = List.copyOf(seen);
        if (seatsPerUser < 1) {
            throw new IllegalStateException(
                    "app.arcade.seats-per-user 는 1 이상이어야 합니다: " + seatsPerUser);
        }
    }

    public boolean knows(String machineId) {
        return machineId != null && machineIds.contains(machineId);
    }
}
