package com.example.ssafesta.game;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which game an arcade machine runs, judged fresh on every request (S15P21A604-602, GitLab #56 안 1).
 *
 * <p>Unity sends only the machine id it placed in the scene; the game behind it is the server's
 * answer. Caching that answer anywhere would let a game that was made private a second ago keep
 * launching, which is why the endpoint is {@code no-store} and this method re-reads both rows.
 *
 * <p><b>An unplayable game is a 200, not an error.</b> The machine is a world fixture standing in
 * front of the player — the world does not break because the game behind it was unpublished, so the
 * response says {@code playable: false} with a reason and the client shows a notice (spec 019
 * FR-020, contracts §unavailableReason 어휘). Only an unknown machine is a {@code 404}: there is
 * nothing to describe, and {@code machineId} is the one field the response cannot leave empty.
 */
@Service
public class ArcadeMachineResolveService {

    private final ArcadeMachineBindingRepository bindings;

    public ArcadeMachineResolveService(ArcadeMachineBindingRepository bindings) {
        this.bindings = bindings;
    }

    @Transactional(readOnly = true)
    public ResolvedView resolve(String machineId) {
        // 바인딩과 게임을 한 질의로 읽는다 — 나눠 읽으면 탈퇴 커밋을 사이에 두고 찢어진다
        // (repository javadoc). 없으면 어느 쪽이 없든 "이 기계는 걸린 게임이 없다" 로 같다.
        Game game = bindings.findBoundGame(machineId)
                .orElseThrow(() -> new ApiException(ErrorCode.MACHINE_NOT_FOUND));

        ErrorCode blocked = GamePublishedQueryService.blockedReason(game);
        boolean playable = blocked == null;
        return new ResolvedView(machineId, game.getId(),
                // Only a playable game states its version. A private game's version number is not
                // a secret worth guarding, but sending it would invite a client to launch on it.
                playable ? game.getPublishedVersion() : null,
                playable, playable ? null : blocked.name());
    }

    /**
     * Every currently installed arcade machine, ordered by the scene's canonical id.
     *
     * <p>Deleted games deliberately disappear rather than report {@code GAME_DELETED}: the scene
     * uses this collection to find empty slots, and a deleted game's binding is no longer an
     * installed machine. Other unavailable states remain visible so the client can explain why a
     * machine it can see cannot start its game.
     */
    @Transactional(readOnly = true)
    public List<ListedView> resolveAll() {
        return bindings.findAllBoundGames().stream()
                .filter(bound -> !bound.getGame().isDeleted())
                .map(bound -> listedView(bound.getMachineId(), bound.getGame()))
                .toList();
    }

    private ListedView listedView(String machineId, Game game) {
        ErrorCode blocked = GamePublishedQueryService.blockedReason(game);
        boolean playable = blocked == null;
        return new ListedView(machineId, game.getId(), playable ? game.getTitle() : null,
                playable ? game.getPublishedVersion() : null,
                playable, playable ? null : blocked.name());
    }

    /**
     * The resolution body (contracts §Booth Portal Resolution, arcade 변형).
     *
     * <p>No {@code boothId}: these machines are world fixtures, so there is no lease to judge and
     * {@code BOOTH_LEASE_EXPIRED} cannot occur here (2026-09-10 배치 결정, docs/26).
     */
    public record ResolvedView(String machineId, Long gameId, Integer publishedVersion,
                               boolean playable, String unavailableReason) {
    }

    /**
     * The collection shape lets the world match an occupied scene slot to its game without a
     * thumbnail.
     *
     * <p><b>Only a playable game names itself.</b> This path is open to anyone, so a private game's
     * title would otherwise be readable by walking the list — the same reason
     * {@link #resolve(String)} withholds {@code publishedVersion} from a game that cannot start.
     * The machine stays listed either way: the scene needs to know the slot is occupied.
     */
    public record ListedView(String machineId, Long gameId, String title, Integer publishedVersion,
                             boolean playable, String unavailableReason) {
    }
}
