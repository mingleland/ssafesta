package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오락실 자리를 잡고 비운다 (S15P21A604-942, GitLab #256).
 *
 * <p><b>자리 잡기는 게시 안에 있다.</b> 게임 파트가 둘을 묶은 이유는 부스에서 겪은 일 때문이다 —
 * 임대만 하고 게시를 안 해 월드에 빈 칸이 남았다(GitLab #238). 오락실에서 같은 일이 나면 프라임
 * 자리에 검은 캐비닛이 선다. 그래서 {@code claimOnPublish} 는 독립 엔드포인트가 아니라
 * {@link GamePublishService#publish} 의 트랜잭션 안에서 불린다 — 게시가 실패하면 자리도 없다.
 *
 * <p><b>이사는 없다.</b> 이미 자리를 가진 게임이 다른 자리로 게시하면 거절한다. 자리를 옮기려면
 * 내렸다 다시 올리며 고른다(게임 파트 확정). 옮겨 주는 코드를 넣으면 "내리면 해제" 와 두 갈래가
 * 되고, 둘 중 어느 쪽이 참인지는 요청 본문에 machineId 가 있느냐로만 갈린다.
 */
@Service
public class ArcadeSeatService {

    private final ArcadeMachineBindingRepository bindings;
    private final ArcadeProperties properties;

    public ArcadeSeatService(ArcadeMachineBindingRepository bindings, ArcadeProperties properties) {
        this.bindings = bindings;
        this.properties = properties;
    }

    /**
     * 게시하면서 자리를 잡는다. 호출자의 트랜잭션 안에서만 돈다.
     *
     * <p>같은 게임이 같은 자리로 다시 게시하는 것은 <b>멱등</b>이다 — 게시는 몇 번이든 할 수 있고,
     * 회차를 올릴 때마다 자리를 다시 잡아야 한다면 그 사이에 남이 자리를 채 갈 수 있다.
     *
     * @throws ApiException {@code MACHINE_NOT_FOUND}(없는 자리) · {@code ARCADE_MACHINE_TAKEN}
     *                      (남의 자리) · {@code ARCADE_SEAT_LIMIT}(한도 초과) ·
     *                      {@code ARCADE_ALREADY_SEATED}(이 게임이 이미 다른 자리에 있다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimOnPublish(Long gameId, Long ownerUserId, String machineId) {
        if (!properties.knows(machineId)) {
            // 씬에 없는 번호다. 통과시키면 아무도 갈 수 없는 자리에 게임이 걸린 채 한도만 먹는다.
            throw new ApiException(ErrorCode.MACHINE_NOT_FOUND);
        }

        List<ArcadeMachineBinding> held = bindings.findByGameIdAndOwnerUserIdNotNull(gameId);
        for (ArcadeMachineBinding seat : held) {
            if (seat.getMachineId().equals(machineId)) {
                return;
            }
        }

        if (!held.isEmpty()) {
            throw new ApiException(ErrorCode.ARCADE_ALREADY_SEATED);
        }

        bindings.findById(machineId).ifPresent(taken -> {
            throw new ApiException(ErrorCode.ARCADE_MACHINE_TAKEN);
        });


        if (bindings.countByOwnerUserId(ownerUserId) >= properties.seatsPerUser()) {
            throw new ApiException(ErrorCode.ARCADE_SEAT_LIMIT,
                    "캐비닛은 한 사람이 " + properties.seatsPerUser() + "대까지 쓸 수 있습니다.");
        }

        // 위의 조회는 먼저 온 사람을 걸러 낼 뿐 경합을 막지 못한다 — 없는 행에는 잠글 것이
        // 없어서, 두 트랜잭션이 같은 빈 자리를 동시에 보고 둘 다 통과한다. 실제로 한 명만 넣는
        // 것은 이 문장이다: 자리가 비어 있을 때만 꽂히고, 차 있으면 0 을 돌려준다.
        if (bindings.insertIfFree(machineId, gameId, ownerUserId) == 0) {
            throw new ApiException(ErrorCode.ARCADE_MACHINE_TAKEN);
        }
    }

    /**
     * 이 게임이 잡고 있던 자리를 비운다 — 게시가 내려갔거나 게임이 삭제됐다.
     *
     * <p>운영자 고정물은 건드리지 않는다. 자리가 없으면 아무 일도 하지 않는다 — 비공개 전환은
     * 자리를 잡은 적 없는 게임에도 일어난다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long gameId) {
        bindings.deleteByGameIdAndOwnerUserIdNotNull(gameId);
    }
}
